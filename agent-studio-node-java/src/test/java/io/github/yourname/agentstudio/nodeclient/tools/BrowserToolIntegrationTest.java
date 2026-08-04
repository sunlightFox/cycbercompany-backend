package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class BrowserToolIntegrationTest {

    @Test
    @EnabledIfSystemProperty(named = "agent.studio.browser.e2e", matches = "true")
    void waitsDiscoversControlsAndVerifiesAFormInteraction() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    <!doctype html><html><body>
                    <label for='task-title'>Task</label>
                    <input id='task-title' name='title' placeholder='Add a task'>
                    <button id='submit' type='button' onclick="window.location.assign('/api/tasks?title=' + encodeURIComponent(document.querySelector('#task-title').value))">Save</button>
                    <p id='chinese'>你好，浏览器</p>
                    <p id='result'></p>
                    <script>setTimeout(() => document.body.insertAdjacentHTML('beforeend', '<button id=loaded>Ready</button>'), 100)</script>
                    </body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path artifactRoot = Files.createTempDirectory("agent-studio-browser-artifacts");
        Map<String, Object> localPolicy = Map.of(
                BrowserNetworkPolicy.POLICY_ARGUMENT,
                Map.of(BrowserNetworkPolicy.ALLOWED_PRIVATE_HOSTS, List.of("127.0.0.1")));
        try (BrowserTool tool = new BrowserTool(HttpClient.newHttpClient(), artifactRoot)) {
            int port = server.getAddress().getPort();
            assertTrue(tool.startTrace("run-a", Map.of()).success());
            assertTrue(tool.open("run-a", merge(localPolicy, "url", "http://127.0.0.1:" + port + "/")).success());
            assertTrue(tool.waitFor("run-a", Map.of("selector", "#loaded", "timeoutMs", 5_000)).success());

            var snapshot = tool.snapshot("run-a", Map.of());
            assertTrue(snapshot.success());
            assertTrue(snapshot.result().get("interactiveElements").toString().contains("#task-title"));
            assertTrue(snapshot.result().get("interactiveElements").toString().contains("#submit"));
            assertTrue(snapshot.result().get("textPreview").toString().contains("你好，浏览器"));

            assertTrue(tool.type("run-a", Map.of("selector", "#task-title", "text", "Browser E2E")).success());
            assertTrue(tool.click("run-a", Map.of("selector", "#submit")).success());
            var verified = tool.verify("run-a", Map.of("checks", List.of(
                    Map.of("type", "textContains", "value", "Saved Browser E2E"),
                    Map.of("type", "visibleSelector", "value", "#result"),
                    Map.of("type", "responseStatus", "value", "201", "urlContains", "/api/tasks"),
                    Map.of("type", "responseUrlContains", "value", "/api/tasks"))));
            assertTrue(verified.success());
            assertTrue(Boolean.TRUE.equals(verified.result().get("verified")));
            // 表单标题在导航 URL 的查询参数中，但网络证据只能返回去除查询参数后的安全路径。
            assertFalse(verified.result().get("checks").toString().contains("title="));
            assertFalse(verified.result().get("checks").toString().contains("Browser%20E2E"));
            assertFalse(tool.verify("run-a", Map.of("checks", List.of(
                    Map.of("type", "textContains", "value", "not-present")))).success());
            var trace = tool.stopTrace("run-a", Map.of());
            assertTrue(trace.success());
            assertTrue(Files.isRegularFile(artifactRoot.resolve(trace.result().get("artifactPath").toString())));
            assertTrue(Long.parseLong(trace.result().get("sizeBytes").toString()) > 0);

            assertTrue(tool.open("run-b", merge(localPolicy, "url", "http://127.0.0.1:" + port + "/")).success());
            assertTrue(tool.waitFor("run-b", Map.of("selector", "#loaded", "timeoutMs", 5_000)).success());
            assertTrue(tool.type("run-b", Map.of("selector", "#task-title", "text", "Other run")).success());
            assertTrue(tool.click("run-b", Map.of("selector", "#submit")).success());
            assertTrue(tool.snapshot("run-b", Map.of()).result().get("textPreview").toString().contains("Saved Other run"));
            assertTrue(tool.snapshot("run-a", Map.of()).result().get("textPreview").toString().contains("Saved Browser E2E"));

            // 第二个页面属于同一个 Run，会话内可以关闭；关闭当前页面后应自动选择仍存活的页面。
            assertTrue(tool.open("run-tabs", merge(localPolicy, "url", "http://127.0.0.1:" + port + "/")).success());
            Map<String, Object> secondTab = merge(localPolicy, "url", "http://127.0.0.1:" + port + "/");
            secondTab.put("newTab", true);
            assertTrue(tool.open("run-tabs", secondTab).success());
            assertTrue(tool.tabs("run-tabs", Map.of()).result().get("tabs").toString().contains("index=1"));
            var closed = tool.closeTab("run-tabs", Map.of("index", 1));
            assertTrue(closed.success());
            assertTrue(Integer.valueOf(1).equals(closed.result().get("tabCount")));
            assertFalse(tool.closeTab("run-tabs", Map.of("index", 0)).success());
            assertTrue(tool.closeSession("run-b"));
            assertFalse(tool.snapshot("run-b", Map.of()).success());
            assertTrue(tool.snapshot("run-a", Map.of()).result().get("textPreview").toString().contains("Saved Browser E2E"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "agent.studio.browser.e2e", matches = "true")
    void exercisesHoverKeyboardSelectAndExplicitDialogHandling() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    <!doctype html><html><body>
                    <input id='search' aria-label='Search'>
                    <select id='priority'><option value='low'>Low</option><option value='high'>High</option></select>
                    <button id='confirm' type='button' onclick="document.querySelector('#result').textContent = confirm('Proceed?') ? 'confirmed' : 'cancelled'">Confirm</button>
                    <p id='result'></p>
                    </body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/tasks", exchange -> {
            byte[] body = "<!doctype html><html><body><p id='result'>Saved Browser E2E</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Map<String, Object> localPolicy = Map.of(
                BrowserNetworkPolicy.POLICY_ARGUMENT,
                Map.of(BrowserNetworkPolicy.ALLOWED_PRIVATE_HOSTS, List.of("127.0.0.1")));
        try (BrowserTool tool = new BrowserTool(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-browser-actions"))) {
            int port = server.getAddress().getPort();
            assertTrue(tool.open("run-actions", merge(localPolicy, "url", "http://127.0.0.1:" + port + "/")).success());
            // 导航得到的 200 可以作为“打开页面”这一步的网络证据。
            assertTrue(tool.verify("run-actions", Map.of("checks", List.of(
                    Map.of("type", "responseStatus", "value", "200")))).success());
            assertTrue(tool.type("run-actions", Map.of("selector", "#search", "text", "task")).success());
            // 输入本身不会发请求，因此不能复用导航时留下的旧 200 来声称当前操作已经调用后端成功。
            assertFalse(tool.verify("run-actions", Map.of("checks", List.of(
                    Map.of("type", "responseStatus", "value", "200")))).success());
            assertTrue(tool.press("run-actions", Map.of("selector", "#search", "key", "End")).success());
            var selected = tool.selectOption("run-actions", Map.of("selector", "#priority", "label", "High"));
            assertTrue(selected.success());
            assertTrue(selected.result().get("selectedValues").toString().contains("high"));
            assertTrue(tool.hover("run-actions", Map.of("selector", "#confirm")).success());
            var clicked = tool.click("run-actions", Map.of(
                    "selector", "#confirm", "dialogAction", "dismiss"));
            assertTrue(clicked.success());
            assertTrue(clicked.result().get("dialog").toString().contains("dismiss"));
            assertTrue(tool.snapshot("run-actions", Map.of()).result().get("textPreview").toString().contains("cancelled"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "agent.studio.browser.e2e", matches = "true")
    void waitsForAnAsynchronousApiResponseThenLeavesAuditableVerificationEvidence() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    <!doctype html><html><body>
                    <button id='save' type='button' onclick="fetch('/api/slow-save?client=browser', {method: 'POST'}).then(() => document.querySelector('#result').textContent = 'saved')">Save</button>
                    <p id='result'></p>
                    </body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/slow-save", exchange -> {
            // 真实前端常在点击后异步保存；故意延迟可覆盖“响应尚未到达”分支。
            try {
                Thread.sleep(180);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        Map<String, Object> localPolicy = Map.of(
                BrowserNetworkPolicy.POLICY_ARGUMENT,
                Map.of(BrowserNetworkPolicy.ALLOWED_PRIVATE_HOSTS, List.of("127.0.0.1")));
        try (BrowserTool tool = new BrowserTool(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-browser-response"))) {
            int port = server.getAddress().getPort();
            assertTrue(tool.open("run-response", merge(localPolicy, "url", "http://127.0.0.1:" + port + "/")).success());
            assertTrue(tool.click("run-response", Map.of("selector", "#save")).success());

            var waited = tool.waitForResponse("run-response", Map.of(
                    "status", 201,
                    "urlContains", "/api/slow-save",
                    "timeoutMs", 5_000));

            assertTrue(waited.success());
            assertTrue(waited.result().get("response").toString().contains("status=201"));
            // 查询参数属于潜在敏感数据，等待证据与 verify 一样只保留路径。
            assertFalse(waited.result().get("response").toString().contains("client=browser"));
            assertTrue(tool.verify("run-response", Map.of("checks", List.of(
                    Map.of("type", "textContains", "value", "saved"),
                    Map.of("type", "responseStatus", "value", "201", "urlContains", "/api/slow-save")))).success());
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, Object> merge(Map<String, Object> source, String key, Object value) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }
}
