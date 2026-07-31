package io.github.yourname.agentstudio.nodeclient.tools;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playwright 浏览器控制工具。
 *
 * <p>这个类运行在节点本机，因此打开的是“节点所在电脑/服务器”的浏览器环境。
 * 第一版使用单浏览器、单页面会话，便于服务端连续调用 open -> click -> type -> screenshot。
 */
public class BrowserTool {

    // 本工具运行在节点本机，且复用一个浏览器/页面会话，适合连续执行 open -> click -> type。

    private Playwright playwright;
    private Browser browser;
    private Page page;

    public BrowserTool(HttpClient httpClient) {
        // 保留构造参数是为了 ToolRegistry 统一注入；Playwright 本身不复用 HttpClient。
    }

    public synchronized ToolExecutionResult open(Map<String, Object> arguments) {
        // synchronized 保护单页面会话，避免并发调用同时导航同一个 page。
        String url = value(arguments, "url");
        if (url == null || url.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: url");
        }
        try {
            Page current = ensurePage(arguments);
            current.navigate(url, new Page.NavigateOptions()
                    .setTimeout(number(arguments, "timeoutMs", 30_000)));
            current.waitForLoadState(LoadState.DOMCONTENTLOADED);
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.open", ex));
        }
    }

    public synchronized ToolExecutionResult snapshot(Map<String, Object> arguments) {
        try {
            Page current = requirePage();
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.snapshot", ex));
        }
    }

    public synchronized ToolExecutionResult screenshot(Map<String, Object> arguments) {
        try {
            Page current = requirePage();
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
        String selector = value(arguments, "selector");
        if (selector == null || selector.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: selector");
        }
        try {
            Page current = requirePage();
            current.click(selector, new Page.ClickOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.click", ex));
        }
    }

    public synchronized ToolExecutionResult type(Map<String, Object> arguments) {
        String selector = value(arguments, "selector");
        String text = value(arguments, "text");
        if (selector == null || selector.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: selector");
        }
        if (text == null) {
            return ToolExecutionResult.failure("Missing required argument: text");
        }
        try {
            Page current = requirePage();
            current.fill(selector, text, new Page.FillOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.type", ex));
        }
    }

    private Page ensurePage(Map<String, Object> arguments) {
        if (playwright == null) {
            playwright = Playwright.create();
        }
        if (browser == null || !browser.isConnected()) {
            // 首次真正需要时才启动 Chromium，避免空闲节点持续占用资源。
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(bool(arguments, "headless", true));
            String channel = value(arguments, "channel");
            if (channel != null && !channel.isBlank()) {
                options.setChannel(channel);
            }
            browser = playwright.chromium().launch(options);
        }
        if (page == null || page.isClosed()) {
            page = browser.newPage();
        }
        return page;
    }

    private Page requirePage() {
        if (page == null || page.isClosed()) {
            throw new IllegalStateException("No active browser page. Call browser.open first.");
        }
        return page;
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
        return result;
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
}
