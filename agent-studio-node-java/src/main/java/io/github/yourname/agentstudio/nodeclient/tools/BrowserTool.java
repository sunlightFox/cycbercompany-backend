package io.github.yourname.agentstudio.nodeclient.tools;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.net.http.HttpClient;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Playwright 浏览器控制工具。
 *
 * <p>这个类运行在节点本机，因此打开的是“节点所在电脑/服务器”的浏览器环境。
 * 第一版使用单浏览器、单页面会话，便于服务端连续调用 open -> click -> type -> screenshot。
 */
public class BrowserTool implements AutoCloseable {

    private static final int MAX_INTERACTIVE_ELEMENTS = 40;
    private static final int MAX_INTERACTIVE_TEXT_LENGTH = 160;
    private static final int MAX_NETWORK_RESPONSES = 100;
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
    private final Path uploadRoot;

    public BrowserTool(HttpClient httpClient) {
        this(httpClient, defaultArtifactRoot());
    }

    public BrowserTool(HttpClient httpClient, Path artifactRoot) {
        this(httpClient, artifactRoot, null);
    }

    public BrowserTool(HttpClient httpClient, Path artifactRoot, Path uploadRoot) {
        // 保留构造参数是为了 ToolRegistry 统一注入；Playwright 本身不复用 HttpClient。
        if (artifactRoot == null) {
            throw new IllegalArgumentException("Browser artifact root is required.");
        }
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
        this.uploadRoot = uploadRoot == null ? null : uploadRoot.toAbsolutePath().normalize();
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
            if (bool(arguments, "newTab", false)) {
                // 复用同一个 BrowserContext 保留登录态，但给当前 URL 分配独立页面。
                current = browserSession.context.newPage();
                browserSession.page = current;
            }
            // 记录本次导航开始前的网络序号。后续响应断言只接受这次导航之后的网络证据。
            beginPageAction(browserSession);
            current.navigate(safeUrl, new Page.NavigateOptions()
                    .setTimeout(number(arguments, "timeoutMs", 30_000)));
            current.waitForLoadState(LoadState.DOMCONTENTLOADED);
            // 重定向后的最终地址必须再次通过检查；不能只相信初始 URL。
            BrowserNetworkPolicy.requireAllowed(current.url(), browserSession.allowedPrivateHosts);
            return ToolExecutionResult.success(pageState(browserSession, current));
        } catch (Exception ex) {
            return failureWithDiagnostics("browser.open", ex, executionSessionId);
        }
    }

    public synchronized ToolExecutionResult snapshot(Map<String, Object> arguments) {
        return snapshot(null, arguments);
    }

    public synchronized ToolExecutionResult snapshot(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            return ToolExecutionResult.success(pageState(browserSession, current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.snapshot", ex));
        }
    }

    /**
     * 对当前页面执行只读断言，帮助模型在点击、输入或导航后获得明确的成功/失败证据。
     * 支持 URL、标题、文本和元素可见性四类断言，最多 20 项，不会刷新快照版本。
     */
    public synchronized ToolExecutionResult verify(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            List<Map<String, Object>> requested = verificationChecks(arguments);
            if (requested.isEmpty()) {
                return ToolExecutionResult.failure("Provide at least one browser verification check.");
            }
            if (requested.size() > 20) {
                return ToolExecutionResult.failure("browser.verify accepts at most 20 checks.");
            }
            String bodyText = null;
            List<Map<String, Object>> results = new ArrayList<>();
            boolean allPassed = true;
            long actionResponseBaseline = browserSession.lastActionResponseSequence;
            for (Map<String, Object> check : requested) {
                String type = value(check, "type");
                String expected = value(check, "value");
                if (type == null || type.isBlank() || expected == null || expected.isBlank()) {
                    return ToolExecutionResult.failure("Each browser.verify check requires type and value.");
                }
                boolean passed;
                String observed;
                switch (type) {
                    case "urlContains" -> {
                        observed = current.url();
                        passed = observed.contains(expected);
                    }
                    case "titleContains" -> {
                        observed = safeTitle(current);
                        passed = observed.contains(expected);
                    }
                    case "textContains" -> {
                        if (bodyText == null) {
                            bodyText = current.textContent("body", new Page.TextContentOptions().setTimeout(3_000));
                        }
                        String fullText = bodyText == null ? "" : bodyText;
                        observed = preview(fullText);
                        passed = fullText.contains(expected);
                    }
                    case "visibleSelector" -> {
                        observed = expected;
                        passed = current.locator(expected).isVisible();
                    }
                    case "responseStatus" -> {
                        int expectedStatus;
                        try {
                            expectedStatus = Integer.parseInt(expected);
                        } catch (NumberFormatException ex) {
                            return ToolExecutionResult.failure("browser.verify responseStatus value must be an HTTP status code.");
                        }
                        if (expectedStatus < 100 || expectedStatus > 599) {
                            return ToolExecutionResult.failure("browser.verify responseStatus value must be between 100 and 599.");
                        }
                        String urlContains = value(check, "urlContains");
                        NetworkResponseEvidence response = latestResponse(
                                browserSession,
                                actionResponseBaseline,
                                candidate -> candidate.status() == expectedStatus
                                        && (urlContains == null || urlContains.isBlank() || candidate.url().contains(urlContains)));
                        observed = response == null ? "no matching response" : response.summary();
                        passed = response != null;
                    }
                    case "responseUrlContains" -> {
                        NetworkResponseEvidence response = latestResponse(
                                browserSession, actionResponseBaseline, candidate -> candidate.url().contains(expected));
                        observed = response == null ? "no matching response" : response.summary();
                        passed = response != null;
                    }
                    default -> {
                        return ToolExecutionResult.failure("Unsupported browser.verify check type: " + type);
                    }
                }
                allPassed &= passed;
                results.add(Map.of(
                        "type", type,
                        "value", boundedCheckValue(expected),
                        "passed", passed,
                        "observed", observed));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("verified", allPassed);
            result.put("checks", results);
            result.put("url", current.url());
            result.put("title", safeTitle(current));
            result.put("snapshotRevision", browserSession.snapshotRevision);
            result.put("networkEvidenceScope", "responses-after-last-page-action");
            return allPassed
                    ? ToolExecutionResult.success(result)
                    : failureWithDiagnostics(
                            "browser.verify", "One or more browser verification checks failed.", executionSessionId, result);
        } catch (Exception ex) {
            return failureWithDiagnostics("browser.verify", ex, executionSessionId);
        }
    }

    private static List<Map<String, Object>> verificationChecks(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("checks");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                return List.of(Map.of("type", "", "value", ""));
            }
            Map<String, Object> check = new LinkedHashMap<>();
            map.forEach((key, value) -> check.put(String.valueOf(key), value));
            checks.add(check);
        }
        return checks;
    }

    private static String boundedCheckValue(String value) {
        return value.length() <= 240 ? value : value.substring(0, 240) + "...";
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
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            current.waitForSelector(selector, new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(browserSession, current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.wait", ex));
        }
    }

    /**
     * 等待最近一次页面操作触发的接口响应。
     *
     * <p>单纯 {@code browser.wait} 只能确认页面元素出现；现代前端可能先乐观更新页面，再由
     * 异步 API 请求真正保存数据。此方法只接受最后一次 open/click/type/press 等页面操作之后的
     * 响应水位线，既可以消费已经到达的响应，也可以短暂等待仍在飞行中的响应。它不返回正文、
     * Cookie、请求头或查询参数，最终交付仍应追加 {@code browser.verify} 形成可审计断言。
     */
    public synchronized ToolExecutionResult waitForResponse(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            Integer expectedStatus = optionalResponseStatus(arguments);
            String urlContains = value(arguments, "urlContains");
            if (expectedStatus == null && (urlContains == null || urlContains.isBlank())) {
                return ToolExecutionResult.failure("browser.wait_response requires status and/or urlContains.");
            }
            long responseBaseline = browserSession.lastActionResponseSequence;
            java.util.function.Predicate<NetworkResponseEvidence> evidenceMatches = evidence ->
                    (expectedStatus == null || evidence.status() == expectedStatus)
                            && (urlContains == null || urlContains.isBlank() || evidence.url().contains(urlContains));
            NetworkResponseEvidence evidence = latestResponse(browserSession, responseBaseline, evidenceMatches);
            if (evidence == null) {
                Response response = current.waitForResponse(
                        candidate -> (expectedStatus == null || candidate.status() == expectedStatus)
                                && (urlContains == null || urlContains.isBlank()
                                        || safeResponseUrl(candidate.url()).contains(urlContains)),
                        new Page.WaitForResponseOptions().setTimeout(number(arguments, "timeoutMs", 10_000)),
                        () -> { });
                // onResponse 监听器通常已记账；再次记录同一条摘要是安全的，并确保等待成功的
                // 响应一定能被紧随其后的 browser.verify 看见。记录内容仍是去参数 URL 和元数据。
                recordNetworkResponse(browserSession, response);
                evidence = latestResponse(browserSession, responseBaseline, evidenceMatches);
            }
            if (evidence == null) {
                return ToolExecutionResult.failure("A matching response arrived but could not be retained as browser evidence.");
            }
            return ToolExecutionResult.success(Map.of(
                    "matched", true,
                    "response", evidence.toMap(),
                    "networkEvidenceScope", "responses-after-last-page-action",
                    "snapshotRevision", browserSession.snapshotRevision));
        } catch (Exception ex) {
            return failureWithDiagnostics("browser.wait_response", ex, executionSessionId);
        }
    }

    public synchronized ToolExecutionResult screenshot(Map<String, Object> arguments) {
        return screenshot(null, arguments);
    }

    public synchronized ToolExecutionResult screenshot(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            boolean fullPage = bool(arguments, "fullPage", true);
            Path outputPath = createArtifactPath(executionSessionId, "screenshots", ".png");
            Files.createDirectories(outputPath.getParent());
            byte[] bytes = current.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(fullPage)
                    .setPath(outputPath));
            Map<String, Object> result = new LinkedHashMap<>(pageState(browserSession, current));
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
            // A trace may be requested before browser.open. Initialize an empty page so the
            // following navigation is captured instead of turning a recoverable ordering
            // difference into a failed run.
            ensurePage(session, arguments);
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
        try {
            BrowserSession browserSession = session(executionSessionId);
            String selector = resolveTarget(browserSession, arguments);
            Page current = requirePage(browserSession);
            beginPageAction(browserSession);
            AtomicReference<Map<String, Object>> dialogState = new AtomicReference<>();
            Consumer<Dialog> dialogHandler = dialogHandler(arguments, dialogState);
            if (dialogHandler != null) {
                current.onDialog(dialogHandler);
            }
            try {
                current.click(selector, new Page.ClickOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            } finally {
                if (dialogHandler != null) {
                    current.offDialog(dialogHandler);
                }
            }
            Map<String, Object> result = new LinkedHashMap<>(pageState(browserSession, current));
            if (dialogState.get() != null) {
                result.put("dialog", dialogState.get());
            }
            return ToolExecutionResult.success(result);
        } catch (Exception ex) {
            return failureWithDiagnostics("browser.click", ex, executionSessionId);
        }
    }

    public synchronized ToolExecutionResult type(Map<String, Object> arguments) {
        return type(null, arguments);
    }

    public synchronized ToolExecutionResult type(String executionSessionId, Map<String, Object> arguments) {
        String text = value(arguments, "text");
        if (text == null) {
            return ToolExecutionResult.failure("Missing required argument: text");
        }
        try {
            BrowserSession browserSession = session(executionSessionId);
            String selector = resolveTarget(browserSession, arguments);
            Page current = requirePage(browserSession);
            beginPageAction(browserSession);
            current.fill(selector, text, new Page.FillOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(browserSession, current));
        } catch (Exception ex) {
            return failureWithDiagnostics("browser.type", ex, executionSessionId);
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
            // 仅保存受网络策略允许的响应摘要：方法、资源类型、状态码和去除查询参数后的 URL。
            // 不保存请求/响应正文、Cookie、Authorization 或响应头，避免把账号和业务数据带入模型上下文。
            session.context.onResponse(response -> recordNetworkResponse(session, response));
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

    private static void recordNetworkResponse(BrowserSession session, Response response) {
        try {
            String url = safeResponseUrl(response.url());
            NetworkResponseEvidence evidence = new NetworkResponseEvidence(
                    session.networkResponseSequence.incrementAndGet(),
                    response.status(),
                    response.request().method(),
                    response.request().resourceType(),
                    url);
            synchronized (session.networkResponses) {
                if (session.networkResponses.size() >= MAX_NETWORK_RESPONSES) {
                    session.networkResponses.remove(0);
                }
                session.networkResponses.add(evidence);
            }
        } catch (Exception ignored) {
            // 响应监听只提供额外证据。单个异常响应不能破坏已完成的页面操作或清理流程。
        }
    }

    /** 校验可选状态码，避免把任意字符串送给 Playwright 的异步等待器。 */
    private static Integer optionalResponseStatus(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("status");
        if (raw == null) {
            return null;
        }
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(raw.toString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("browser.wait_response status must be an HTTP status code.");
        }
        if (value < 100 || value > 599) {
            throw new IllegalArgumentException("browser.wait_response status must be between 100 and 599.");
        }
        return value;
    }

    private static String safeResponseUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            // getRawAuthority() 可能含有 user:password@host，不能用于交付证据或模型上下文。
            // 这里显式重建 authority，只留下主机和端口；IPv6 主机需要恢复方括号。
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getScheme() == null || uri.getScheme().isBlank()) {
                return "unparseable-response-url";
            }
            String hostPart = host.contains(":") ? "[" + host + "]" : host;
            String authority = "//" + hostPart + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            return uri.getScheme() + ":" + authority + path;
        } catch (Exception ignored) {
            // URL 不可解析时不回显原始文本，避免意外保留嵌在 URL 内的访问令牌或账号密码。
            return "unparseable-response-url";
        }
    }

    private static NetworkResponseEvidence latestResponse(
            BrowserSession session,
            long exclusiveSequence,
            java.util.function.Predicate<NetworkResponseEvidence> predicate) {
        synchronized (session.networkResponses) {
            for (int index = session.networkResponses.size() - 1; index >= 0; index--) {
                NetworkResponseEvidence candidate = session.networkResponses.get(index);
                if (candidate.sequence() > exclusiveSequence && predicate.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private Page requirePage(BrowserSession session) {
        if (session.page == null || session.page.isClosed()) {
            throw new IllegalStateException("No active browser page. Call browser.open first.");
        }
        return session.page;
    }

    /**
     * 为一次会改变页面状态的操作创建网络证据水位线。
     *
     * <p>响应监听在 Playwright 的异步线程中运行，因此使用 AtomicLong 取得稳定序号。
     * 验证阶段只接受序号严格大于该值的响应，避免把历史 API 成功记录混入当前表单提交、
     * 上传或导航的交付证据。
     */
    private static void beginPageAction(BrowserSession session) {
        session.lastActionResponseSequence = session.networkResponseSequence.get();
    }

    /**
     * 每次返回可交互元素时都建立一个新的快照版本。后续 mutation 带 ref 时必须同时携带
     * 该版本号；页面已经导航或发生交互后，旧 ref 会被拒绝而不是猜测 CSS 选择器仍有效。
     */
    private Map<String, Object> pageState(BrowserSession session, Page current) {
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
        session.snapshotRevision++;
        session.refs.clear();
        List<Map<String, Object>> elements = interactiveElements(current);
        for (int index = 0; index < elements.size(); index++) {
            Map<String, Object> element = elements.get(index);
            String ref = "e" + session.snapshotRevision + "_" + (index + 1);
            session.refs.put(ref, element.get("selector").toString());
            element.put("ref", ref);
        }
        result.put("snapshotRevision", session.snapshotRevision);
        result.put("interactiveElements", elements);
        return result;
    }

    /** 返回当前 BrowserContext 的标签页摘要，不把网页正文当成控制指令。 */
    public synchronized ToolExecutionResult tabs(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession browserSession = session(executionSessionId);
            if (browserSession.context == null) {
                return ToolExecutionResult.failure("No active browser context. Call browser.open first.");
            }
            List<Map<String, Object>> tabs = new ArrayList<>();
            Page[] pages = browserSession.context.pages().toArray(Page[]::new);
            for (int index = 0; index < pages.length; index++) {
                Page page = pages[index];
                tabs.add(Map.of(
                        "index", index,
                        "active", page == browserSession.page,
                        "url", page.url(),
                        "title", safeTitle(page)));
            }
            return ToolExecutionResult.success(Map.of("tabs", tabs, "activeIndex", activeTabIndex(pages, browserSession.page)));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.tabs", ex));
        }
    }

    /** 将鼠标移动到一个元素上，常用于触发菜单、提示或懒加载内容。 */
    public synchronized ToolExecutionResult hover(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession browserSession = session(executionSessionId);
            String selector = resolveTarget(browserSession, arguments);
            Page current = requirePage(browserSession);
            beginPageAction(browserSession);
            current.hover(selector, new Page.HoverOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            return ToolExecutionResult.success(pageState(browserSession, current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.hover", ex));
        }
    }

    /** 对当前焦点元素或指定元素发送一个标准 Playwright 键值，例如 Enter、Control+A。 */
    public synchronized ToolExecutionResult press(String executionSessionId, Map<String, Object> arguments) {
        String key = value(arguments, "key");
        if (key == null || key.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: key");
        }
        if (key.length() > 120) {
            return ToolExecutionResult.failure("key is too long.");
        }
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            String selector = value(arguments, "selector");
            String ref = value(arguments, "ref");
            if ((selector == null || selector.isBlank()) && (ref == null || ref.isBlank())) {
                beginPageAction(browserSession);
                current.keyboard().press(key);
            } else {
                beginPageAction(browserSession);
                current.press(resolveTarget(browserSession, arguments), key,
                        new Page.PressOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            }
            return ToolExecutionResult.success(pageState(browserSession, current));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.press", ex));
        }
    }

    /** 选择原生 HTML select 的 value、label 或 index 中的一个。 */
    public synchronized ToolExecutionResult selectOption(String executionSessionId, Map<String, Object> arguments) {
        String value = value(arguments, "value");
        String label = value(arguments, "label");
        Object rawIndex = arguments == null ? null : arguments.get("index");
        if ((value == null || value.isBlank()) && (label == null || label.isBlank()) && !(rawIndex instanceof Number)) {
            return ToolExecutionResult.failure("Provide one of value, label, or index.");
        }
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            String selector = resolveTarget(browserSession, arguments);
            beginPageAction(browserSession);
            SelectOption option = new SelectOption();
            if (value != null && !value.isBlank()) {
                option.setValue(value);
            }
            if (label != null && !label.isBlank()) {
                option.setLabel(label);
            }
            if (rawIndex instanceof Number number) {
                int index = number.intValue();
                if (index < 0) {
                    return ToolExecutionResult.failure("index must be non-negative.");
                }
                option.setIndex(index);
            }
            List<String> selected = current.selectOption(selector, option,
                    new Page.SelectOptionOptions().setTimeout(number(arguments, "timeoutMs", 10_000)));
            Map<String, Object> result = new LinkedHashMap<>(pageState(browserSession, current));
            result.put("selectedValues", selected);
            return ToolExecutionResult.success(result);
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.select_option", ex));
        }
    }

    /**
     * 为一次点击安装显式的弹窗策略。没有 dialogAction 时不注册监听器，避免吞掉未知弹窗。
     */
    private static Consumer<Dialog> dialogHandler(Map<String, Object> arguments,
            AtomicReference<Map<String, Object>> dialogState) {
        String action = value(arguments, "dialogAction");
        if (action == null || action.isBlank()) {
            return null;
        }
        String normalized = action.toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("accept", "dismiss").contains(normalized)) {
            throw new IllegalArgumentException("dialogAction must be accept or dismiss.");
        }
        String promptText = value(arguments, "dialogPrompt");
        return dialog -> {
            if ("accept".equals(normalized)) {
                if (promptText == null) {
                    dialog.accept();
                } else {
                    dialog.accept(promptText);
                }
            } else {
                dialog.dismiss();
            }
            dialogState.set(Map.of(
                    "type", dialog.type(),
                    "message", preview(dialog.message()),
                    "action", normalized));
        };
    }

    public synchronized ToolExecutionResult switchTab(String executionSessionId, Map<String, Object> arguments) {
        Object rawIndex = arguments == null ? null : arguments.get("index");
        if (!(rawIndex instanceof Number number)) {
            return ToolExecutionResult.failure("Missing required argument: index");
        }
        try {
            BrowserSession browserSession = session(executionSessionId);
            if (browserSession.context == null) {
                return ToolExecutionResult.failure("No active browser context. Call browser.open first.");
            }
            List<Page> pages = browserSession.context.pages();
            int index = number.intValue();
            if (index < 0 || index >= pages.size()) {
                return ToolExecutionResult.failure("Browser tab index is out of range.");
            }
            browserSession.page = pages.get(index);
            browserSession.refs.clear();
            beginPageAction(browserSession);
            return ToolExecutionResult.success(pageState(browserSession, browserSession.page));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.switch_tab", ex));
        }
    }

    /**
     * 关闭当前 Run 浏览器会话中的一个标签页。关闭当前页时会自动选择相邻仍存活的标签，
     * 不允许关闭唯一页面，避免后续工具调用落入一个没有活动页面的模糊状态。
     */
    public synchronized ToolExecutionResult closeTab(String executionSessionId, Map<String, Object> arguments) {
        Object rawIndex = arguments == null ? null : arguments.get("index");
        if (!(rawIndex instanceof Number number)) {
            return ToolExecutionResult.failure("Missing required argument: index");
        }
        try {
            BrowserSession browserSession = session(executionSessionId);
            if (browserSession.context == null) {
                return ToolExecutionResult.failure("No active browser context. Call browser.open first.");
            }
            List<Page> pages = browserSession.context.pages();
            int index = number.intValue();
            if (index < 0 || index >= pages.size()) {
                return ToolExecutionResult.failure("Browser tab index is out of range.");
            }
            if (pages.size() <= 1) {
                return ToolExecutionResult.failure("Refusing to close the only browser tab. Close the run session instead.");
            }
            Page closing = pages.get(index);
            boolean wasActive = closing == browserSession.page;
            closing.close();
            List<Page> remaining = browserSession.context.pages();
            if (wasActive || browserSession.page == null || browserSession.page.isClosed()) {
                browserSession.page = remaining.get(Math.min(index, remaining.size() - 1));
            }
            browserSession.refs.clear();
            beginPageAction(browserSession);
            Map<String, Object> result = new LinkedHashMap<>(pageState(browserSession, browserSession.page));
            result.put("closedIndex", index);
            result.put("tabCount", remaining.size());
            return ToolExecutionResult.success(result);
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.close_tab", ex));
        }
    }

    /** 等待一次由页面触发的下载，并把文件放进节点 artifact 根目录。 */
    public synchronized ToolExecutionResult download(String executionSessionId, Map<String, Object> arguments) {
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            String selector = resolveTarget(browserSession, arguments);
            beginPageAction(browserSession);
            Download download = current.waitForDownload(() -> current.click(selector));
            String suggested = download.suggestedFilename();
            String safeName = suggested == null ? "download.bin" : suggested.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safeName.isBlank() || ".".equals(safeName) || "..".equals(safeName)) {
                safeName = "download.bin";
            }
            Path target = createArtifactPath(executionSessionId, "downloads", "-" + safeName);
            Files.createDirectories(target.getParent());
            download.saveAs(target);
            return ToolExecutionResult.success(Map.of(
                    "artifactType", "browser-download",
                    "artifactPath", relativeArtifactPath(target),
                    "suggestedFilename", safeName,
                    "sizeBytes", Files.size(target),
                    "snapshotRevision", browserSession.snapshotRevision));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.download", ex));
        }
    }

    /** 上传前检查真实路径，避免网页工具借机读取节点任意文件。 */
    public synchronized ToolExecutionResult upload(String executionSessionId, Map<String, Object> arguments) {
        if (uploadRoot == null) {
            return ToolExecutionResult.failure("browser.upload is unavailable without a configured workspace root.");
        }
        try {
            BrowserSession browserSession = session(executionSessionId);
            Page current = requirePage(browserSession);
            String selector = resolveTarget(browserSession, arguments);
            String requestedPath = value(arguments, "path");
            if (requestedPath == null || requestedPath.isBlank()) {
                return ToolExecutionResult.failure("Missing required argument: path");
            }
            Path file = Path.of(requestedPath).toAbsolutePath().normalize().toRealPath();
            if (!file.startsWith(uploadRoot) || !Files.isRegularFile(file)) {
                return ToolExecutionResult.failure("Upload path must be a regular file inside the configured workspace.");
            }
            beginPageAction(browserSession);
            current.setInputFiles(selector, file);
            return ToolExecutionResult.success(Map.of(
                    "uploaded", true,
                    "path", uploadRoot.relativize(file).toString().replace('\\', '/'),
                    "snapshotRevision", browserSession.snapshotRevision));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(playwrightError("browser.upload", ex));
        }
    }

    private static String resolveTarget(BrowserSession session, Map<String, Object> arguments) {
        String ref = value(arguments, "ref");
        if (ref == null || ref.isBlank()) {
            String selector = value(arguments, "selector");
            if (selector == null || selector.isBlank()) {
                throw new IllegalArgumentException("Provide selector or a snapshot ref.");
            }
            return selector;
        }
        Object requestedRevision = arguments == null ? null : arguments.get("snapshotRevision");
        if (!(requestedRevision instanceof Number number) || number.intValue() != session.snapshotRevision) {
            throw new IllegalArgumentException("Browser ref belongs to an older snapshot; request browser.snapshot again.");
        }
        String selector = session.refs.get(ref);
        if (selector == null) {
            throw new IllegalArgumentException("Unknown browser ref " + ref + "; request browser.snapshot again.");
        }
        return selector;
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

    /**
     * 在核心页面操作失败时保留可复盘证据。
     *
     * <p>已经显式开启的 Trace 优先封存，因为它包含交互与页面快照；否则保存当前页面
     * 截图。Artifact 上传后会由节点传输层删除本地文件，服务端只收到受控引用。
     */
    private ToolExecutionResult failureWithDiagnostics(String operation, Exception error, String executionSessionId) {
        return failureWithDiagnostics(operation, playwrightError(operation, error), executionSessionId, Map.of());
    }

    private ToolExecutionResult failureWithDiagnostics(
            String operation, String errorMessage, String executionSessionId, Map<String, Object> existingResult) {
        Map<String, Object> result = new LinkedHashMap<>(existingResult == null ? Map.of() : existingResult);
        result.put("operation", operation);
        result.put("diagnosticArtifactCaptured", false);
        BrowserSession session = sessions.get(sessionKey(executionSessionId));
        if (session == null || session.page == null || session.page.isClosed()) {
            return ToolExecutionResult.failure(Map.copyOf(result), errorMessage);
        }
        try {
            Path output;
            if (session.traceRecording && session.context != null) {
                output = createArtifactPath(executionSessionId, "failure-traces", ".zip");
                Files.createDirectories(output.getParent());
                session.context.tracing().stop(new Tracing.StopOptions().setPath(output));
                session.traceRecording = false;
                result.put("artifactType", "playwright-failure-trace");
                result.put("mimeType", "application/zip");
            } else {
                output = createArtifactPath(executionSessionId, "failure-screenshots", ".png");
                Files.createDirectories(output.getParent());
                session.page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(output));
                result.put("artifactType", "browser-failure-screenshot");
                result.put("mimeType", "image/png");
            }
            result.put("artifactPath", relativeArtifactPath(output));
            result.put("sizeBytes", Files.size(output));
            result.put("diagnosticArtifactCaptured", true);
        } catch (Exception ignored) {
            // 保留原始操作错误；取证失败不能掩盖真正需要模型恢复的页面错误。
            result.put("diagnosticCaptureFailed", true);
        }
        return ToolExecutionResult.failure(Map.copyOf(result), errorMessage);
    }

    private static final class BrowserSession {
        private Playwright playwright;
        private Browser browser;
        private BrowserContext context;
        private Page page;
        private boolean traceRecording;
        private Set<String> allowedPrivateHosts = Set.of();
        private int snapshotRevision;
        private final Map<String, String> refs = new LinkedHashMap<>();
        private final List<NetworkResponseEvidence> networkResponses = new ArrayList<>();
        /** 所有已记录响应的递增序号，允许异步监听器与同步工具调用安全协作。 */
        private final AtomicLong networkResponseSequence = new AtomicLong();
        /** 最近一次页面状态操作开始前的序号，即当前响应断言的历史水位线。 */
        private volatile long lastActionResponseSequence;
    }

    /** 浏览器网络证据的最小摘要，禁止携带响应正文、响应头和 URL 查询参数。 */
    private record NetworkResponseEvidence(long sequence, int status, String method, String resourceType, String url) {

        private String summary() {
            return method + " " + resourceType + " " + status + " " + url;
        }

        private Map<String, Object> toMap() {
            return Map.of(
                    "status", status,
                    "method", method,
                    "resourceType", resourceType,
                    "url", url);
        }
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int activeTabIndex(Page[] pages, Page active) {
        for (int index = 0; index < pages.length; index++) {
            if (pages[index] == active) return index;
        }
        return -1;
    }
}
