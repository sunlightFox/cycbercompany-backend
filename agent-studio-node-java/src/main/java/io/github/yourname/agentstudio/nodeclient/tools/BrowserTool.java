package io.github.yourname.agentstudio.nodeclient.tools;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playwright 浏览器控制工具。
 *
 * <p>这个类运行在节点本机，因此打开的是“节点所在电脑/服务器”的浏览器环境。
 * 第一版使用单浏览器、单页面会话，便于服务端连续调用 open -> click -> type -> screenshot。
 */
public class BrowserTool implements AutoCloseable {

    private static final int MAX_INTERACTIVE_ELEMENTS = 40;
    private static final int MAX_INTERACTIVE_TEXT_LENGTH = 160;
    private static final String DEFAULT_SESSION_ID = "default";
    private static final String INTERACTIVE_ELEMENTS_SCRIPT = """
            () => {
              const visible = (element) => {
                const style = window.getComputedStyle(element);
                const rect = element.getBoundingClientRect();
                return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
              };
              const selector = (element) => {
                const escape = (value) => CSS.escape(String(value));
                const parts = [];
                let current = element;
                while (current && current.nodeType === Node.ELEMENT_NODE && current !== document.body) {
                  if (current.id) {
                    parts.unshift('#' + escape(current.id));
                    break;
                  }
                  const tag = current.tagName.toLowerCase();
                  if (current.dataset && current.dataset.testid) {
                    parts.unshift(tag + '[data-testid="' + escape(current.dataset.testid) + '"]');
                  } else if (current.getAttribute('name')) {
                    parts.unshift(tag + '[name="' + escape(current.getAttribute('name')) + '"]');
                  } else {
                    const siblings = Array.from(current.parentElement ? current.parentElement.children : [])
                      .filter((sibling) => sibling.tagName === current.tagName);
                    parts.unshift(tag + ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')');
                  }
                  current = current.parentElement;
                }
                return parts.join(' > ');
              };
              return Array.from(document.querySelectorAll(
                'button, input, textarea, select, a[href], [role="button"], [role="link"], [contenteditable="true"]'
              ))
                .filter(visible)
                .slice(0, 40)
                .map((element) => ({
                  selector: selector(element),
                  tag: element.tagName.toLowerCase(),
                  role: element.getAttribute('role') || '',
                  type: element.getAttribute('type') || '',
                  name: element.getAttribute('name') || element.getAttribute('aria-label') || '',
                  placeholder: element.getAttribute('placeholder') || '',
                  text: (element.innerText || element.value || element.getAttribute('aria-label') || '').trim(),
                  disabled: Boolean(element.disabled || element.getAttribute('aria-disabled') === 'true')
                }));
            }
            """;

    // 本工具运行在节点本机，且复用一个浏览器/页面会话，适合连续执行 open -> click -> type。

    private final Map<String, BrowserSession> sessions = new LinkedHashMap<>();

    public BrowserTool(HttpClient httpClient) {
        // 保留构造参数是为了 ToolRegistry 统一注入；Playwright 本身不复用 HttpClient。
    }

    public synchronized ToolExecutionResult open(Map<String, Object> arguments) {
        return open(null, arguments);
    }

    public synchronized ToolExecutionResult open(String executionSessionId, Map<String, Object> arguments) {
        // synchronized 保护单页面会话，避免并发调用同时导航同一个 page。
        String url = value(arguments, "url");
        if (url == null || url.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: url");
        }
        try {
            Page current = ensurePage(session(executionSessionId), arguments);
            current.navigate(url, new Page.NavigateOptions()
                    .setTimeout(number(arguments, "timeoutMs", 30_000)));
            current.waitForLoadState(LoadState.DOMCONTENTLOADED);
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.open", ex));
        }
    }

    public synchronized ToolExecutionResult snapshot(Map<String, Object> arguments) {
        return snapshot(null, arguments);
    }

    public synchronized ToolExecutionResult snapshot(String executionSessionId, Map<String, Object> arguments) {
        try {
            Page current = requirePage(session(executionSessionId));
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.snapshot", ex));
        }
    }

    /** Waits for a visible element before returning the inspectable page state. */
    public synchronized ToolExecutionResult waitFor(Map<String, Object> arguments) {
        return waitFor(null, arguments);
    }

    public synchronized ToolExecutionResult waitFor(String executionSessionId, Map<String, Object> arguments) {
        String selector = value(arguments, "selector");
        if (selector == null || selector.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: selector");
        }
        try {
            Page current = requirePage(session(executionSessionId));
            current.waitForSelector(selector, new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.wait", ex));
        }
    }

    public synchronized ToolExecutionResult screenshot(Map<String, Object> arguments) {
        return screenshot(null, arguments);
    }

    public synchronized ToolExecutionResult screenshot(String executionSessionId, Map<String, Object> arguments) {
        try {
            Page current = requirePage(session(executionSessionId));
            boolean fullPage = bool(arguments, "fullPage", true);
            Path outputPath = screenshotPath(arguments);
            // 调用方可指定输出位置；生产版还应限制到节点允许写入的目录。
            Files.createDirectories(outputPath.getParent());
            byte[] bytes = current.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(fullPage)
                    .setPath(outputPath));
            Map<String, Object> result = new LinkedHashMap<>(pageState(current));
            result.put("mimeType", "image/png");
            result.put("byteLength", bytes.length);
            result.put("path", outputPath.toAbsolutePath().normalize().toString());
            return ToolExecutionResult.success(result);
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.screenshot", ex));
        }
    }

    public synchronized ToolExecutionResult click(Map<String, Object> arguments) {
        return click(null, arguments);
    }

    public synchronized ToolExecutionResult click(String executionSessionId, Map<String, Object> arguments) {
        String selector = value(arguments, "selector");
        if (selector == null || selector.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: selector");
        }
        try {
            Page current = requirePage(session(executionSessionId));
            current.click(selector, new Page.ClickOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.click", ex));
        }
    }

    public synchronized ToolExecutionResult type(Map<String, Object> arguments) {
        return type(null, arguments);
    }

    public synchronized ToolExecutionResult type(String executionSessionId, Map<String, Object> arguments) {
        String selector = value(arguments, "selector");
        String text = value(arguments, "text");
        if (selector == null || selector.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: selector");
        }
        if (text == null) {
            return ToolExecutionResult.failure("Missing required argument: text");
        }
        try {
            Page current = requirePage(session(executionSessionId));
            current.fill(selector, text, new Page.FillOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.type", ex));
        }
    }

    @Override
    public synchronized void close() {
        for (BrowserSession session : sessions.values()) {
            closeSession(session);
        }
        sessions.clear();
    }

    /** Closes only one backend-owned execution session. It is not exposed as a model tool. */
    public synchronized boolean closeSession(String executionSessionId) {
        BrowserSession session = sessions.remove(sessionKey(executionSessionId));
        if (session == null) {
            return false;
        }
        closeSession(session);
        return true;
    }

    private BrowserSession session(String executionSessionId) {
        return sessions.computeIfAbsent(sessionKey(executionSessionId), ignored -> new BrowserSession());
    }

    private static String sessionKey(String executionSessionId) {
        return executionSessionId == null || executionSessionId.isBlank()
                ? DEFAULT_SESSION_ID
                : executionSessionId;
    }

    private static void closeSession(BrowserSession session) {
        if (session.page != null) {
            try {
                session.page.close();
            } catch (Exception ignored) {
                // Closing a detached page must not prevent the node from shutting down.
            }
            session.page = null;
        }
        if (session.browser != null) {
            try {
                session.browser.close();
            } catch (Exception ignored) {
                // The browser may already have been closed externally.
            }
            session.browser = null;
        }
        if (session.playwright != null) {
            try {
                session.playwright.close();
            } catch (Exception ignored) {
                // Playwright owns native resources; best-effort cleanup is sufficient here.
            }
            session.playwright = null;
        }
    }

    private Page ensurePage(BrowserSession session, Map<String, Object> arguments) {
        if (session.playwright == null) {
            session.playwright = Playwright.create();
        }
        if (session.browser == null || !session.browser.isConnected()) {
            // 首次真正需要时才启动 Chromium，避免空闲节点持续占用资源。
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(bool(arguments, "headless", true));
            String channel = value(arguments, "channel");
            if (channel != null && !channel.isBlank()) {
                options.setChannel(channel);
            }
            session.browser = session.playwright.chromium().launch(options);
        }
        if (session.page == null || session.page.isClosed()) {
            session.page = session.browser.newPage();
        }
        return session.page;
    }

    private Page requirePage(BrowserSession session) {
        if (session.page == null || session.page.isClosed()) {
            throw new IllegalStateException("No active browser page. Call browser.open first.");
        }
        return session.page;
    }

    private Map<String, Object> pageState(Page current) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", current.url());
        result.put("title", current.title());
        String text = "";
        try {
            String bodyText = current.textContent("body", new Page.TextContentOptions().setTimeout(3_000));
            text = bodyText == null ? "" : bodyText;
        } catch (Exception ignored) {
            // 某些页面没有 body 或阻塞 innerText；页面状态仍然可以通过 URL/title 返回。
        }
        result.put("textPreview", preview(text));
        result.put("textLength", text.length());
        result.put("interactiveElements", interactiveElements(current));
        return result;
    }

    private static List<Map<String, Object>> interactiveElements(Page current) {
        try {
            return normalizeInteractiveElements(current.evaluate(INTERACTIVE_ELEMENTS_SCRIPT));
        } catch (Exception ignored) {
            // Page inspection remains useful even when an unusual page blocks script evaluation.
            return List.of();
        }
    }

    static List<Map<String, Object>> normalizeInteractiveElements(Object rawElements) {
        if (!(rawElements instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        for (Object rawElement : iterable) {
            if (elements.size() >= MAX_INTERACTIVE_ELEMENTS || !(rawElement instanceof Map<?, ?> map)) {
                continue;
            }
            String selector = stringValue(map.get("selector"));
            if (selector.isBlank()) {
                continue;
            }
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("selector", selector);
            copyTextField(map, element, "tag", 40);
            copyTextField(map, element, "role", 80);
            copyTextField(map, element, "type", 80);
            copyTextField(map, element, "name", 160);
            copyTextField(map, element, "placeholder", 160);
            copyTextField(map, element, "text", MAX_INTERACTIVE_TEXT_LENGTH);
            element.put("disabled", Boolean.TRUE.equals(map.get("disabled")));
            elements.add(element);
        }
        return List.copyOf(elements);
    }

    private static void copyTextField(Map<?, ?> source, Map<String, Object> target, String name, int maxLength) {
        String value = stringValue(source.get(name));
        target.put(name, value.length() <= maxLength ? value : value.substring(0, maxLength) + "...");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String value(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static Path screenshotPath(Map<String, Object> arguments) {
        String configured = value(arguments, "path");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        // 默认写入临时目录，避免把演示截图混入项目工作区。
        String safeName = "browser-" + Instant.now().toString().replace(":", "-").replace(".", "-") + ".png";
        return Path.of(System.getProperty("java.io.tmpdir"), "agent-studio-node", "screenshots", safeName)
                .toAbsolutePath()
                .normalize();
    }

    private static boolean bool(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static double number(Map<String, Object> arguments, String key, double fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    private static String playwrightError(String toolName, Exception ex) {
        String message = ex instanceof TimeoutError ? "Timed out: " + ex.getMessage() : ex.getMessage();
        if (message != null && message.contains("Executable doesn't exist")) {
            return toolName + " failed because Playwright browser binaries are not installed. Run: "
                    + "gradlew :agent-studio-node-java:run --args=\"install-browsers\"";
        }
        return toolName + " failed: " + message;
    }

    private static final class BrowserSession {
        private Playwright playwright;
        private Browser browser;
        private Page page;
    }
}
