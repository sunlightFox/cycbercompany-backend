package io.github.yourname.agentstudio.nodeclient.tools;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final Path artifactRoot;

    public BrowserTool(HttpClient httpClient) {
        this(httpClient, defaultArtifactRoot());
    }

    BrowserTool(HttpClient httpClient, Path artifactRoot) {
        // 保留构造参数是为了 ToolRegistry 统一注入；Playwright 本身不复用 HttpClient。
        if (artifactRoot == null) {
            throw new IllegalArgumentException("Browser artifact root is required.");
        }
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
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
            BrowserSession browserSession = session(executionSessionId);
            Set<String> allowedPrivateHosts = BrowserNetworkPolicy.allowedPrivateHosts(arguments);
            String safeUrl = BrowserNetworkPolicy.requireAllowed(url, allowedPrivateHosts);
            browserSession.allowedPrivateHosts = allowedPrivateHosts;
            Page current = ensurePage(browserSession, arguments);
            current.navigate(safeUrl, new Page.NavigateOptions()
                    .setTimeout(number(arguments, "timeoutMs", 30_000)));
            current.waitForLoadState(LoadState.DOMCONTENTLOADED);
            // 重定向后的最终地址必须再次通过检查；不能只相信初始 URL。
            BrowserNetworkPolicy.requireAllowed(current.url(), browserSession.allowedPrivateHosts);
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
            Path outputPath = createArtifactPath(executionSessionId, "screenshots", ".png");
            Files.createDirectories(outputPath.getParent());
            byte[] bytes = current.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(fullPage)
                    .setPath(outputPath));
            Map<String, Object> result = new LinkedHashMap<>(pageState(current));
            result.put("mimeType", "image/png");
            result.put("byteLength", bytes.length);
            result.put("artifactPath", relativeArtifactPath(outputPath));
            return ToolExecutionResult.success(result);
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.screenshot", ex));
        }
    }

    /**
     * 开始记录当前会话的 Playwright Trace。
     *
     * <p>服务端决定何时调用此命令以及 Trace 是否构成交付证据；客户端只记录当前
     * 已存在的浏览器会话，不读取账号资料或将 Trace 上传到第三方服务。
     */
    public synchronized ToolExecutionResult startTrace(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession session = session(executionSessionId);
            requirePage(session);
            if (session.traceRecording) {
                return ToolExecutionResult.success(Map.of("recording", true, "alreadyRecording", true));
            }
            session.context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(false));
            session.traceRecording = true;
            return ToolExecutionResult.success(Map.of("recording", true, "alreadyRecording", false));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.trace.start", ex));
        }
    }

    /** Stops tracing and writes a local ZIP artifact for later authorized inspection. */
    public synchronized ToolExecutionResult stopTrace(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession session = session(executionSessionId);
            requirePage(session);
            if (!session.traceRecording) {
                return ToolExecutionResult.failure("No active browser trace. Call browser.trace.start first.");
            }
            Path output = createArtifactPath(executionSessionId, "traces", ".zip");
            Files.createDirectories(output.getParent());
            session.context.tracing().stop(new Tracing.StopOptions().setPath(output));
            session.traceRecording = false;
            return ToolExecutionResult.success(Map.of(
                    "recording", false,
                    "artifactType", "playwright-trace",
                    "artifactPath", relativeArtifactPath(output),
                    "sizeBytes", Files.size(output)));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.trace.stop", ex));
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
        if (session.traceRecording && session.context != null) {
            try {
                // 未显式导出的 Trace 不作为交付物保留，避免取消任务时产生无主文件。
                session.context.tracing().stop();
            } catch (Exception ignored) {
                // 浏览器上下文关闭时可能已经停止追踪。
            }
            session.traceRecording = false;
        }
        if (session.context != null) {
            try {
                session.context.close();
            } catch (Exception ignored) {
                // 上下文可能已随着浏览器关闭，继续释放其余资源。
            }
            session.context = null;
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
            session.context = session.browser.newContext();
            session.context.route("**/*", route -> {
                try {
                    BrowserNetworkPolicy.requireAllowed(route.request().url(), session.allowedPrivateHosts);
                    route.resume();
                } catch (IllegalArgumentException blocked) {
                    // 对主导航的中止会由 navigate 返回失败；对子资源则保持页面与本机网络隔离。
                    route.abort();
                }
            });
            session.page = session.context.newPage();
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

    /** 生成的路径只由节点决定，调用参数无法影响目录或文件名。 */
    Path createArtifactPath(String executionSessionId, String category, String extension) {
        String safeSession = sessionKey(executionSessionId).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeSession.length() > 80) {
            safeSession = safeSession.substring(0, 80);
        }
        Path output = artifactRoot
                .resolve("browser")
                .resolve(safeSession)
                .resolve(category)
                .resolve(UUID.randomUUID() + extension)
                .normalize();
        if (!output.startsWith(artifactRoot)) {
            throw new IllegalStateException("Generated browser artifact path escaped the artifact root.");
        }
        return output;
    }

    private String relativeArtifactPath(Path path) {
        return artifactRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static Path defaultArtifactRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "agent-studio-node", "artifacts")
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
        private BrowserContext context;
        private Page page;
        private boolean traceRecording;
        private Set<String> allowedPrivateHosts = Set.of();
    }
}
