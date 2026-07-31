package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
                    <button id='submit' type='button' onclick="document.querySelector('#result').textContent = 'Saved ' + document.querySelector('#task-title').value">Save</button>
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
        try (BrowserTool tool = new BrowserTool(HttpClient.newHttpClient())) {
            int port = server.getAddress().getPort();
            assertTrue(tool.open("run-a", Map.of("url", "http://127.0.0.1:" + port + "/")).success());
            assertTrue(tool.waitFor("run-a", Map.of("selector", "#loaded", "timeoutMs", 5_000)).success());

            var snapshot = tool.snapshot("run-a", Map.of());
            assertTrue(snapshot.success());
            assertTrue(snapshot.result().get("interactiveElements").toString().contains("#task-title"));
            assertTrue(snapshot.result().get("interactiveElements").toString().contains("#submit"));

            assertTrue(tool.type("run-a", Map.of("selector", "#task-title", "text", "Browser E2E")).success());
            assertTrue(tool.click("run-a", Map.of("selector", "#submit")).success());
            assertTrue(tool.snapshot("run-a", Map.of()).result().get("textPreview").toString().contains("Saved Browser E2E"));

            assertTrue(tool.open("run-b", Map.of("url", "http://127.0.0.1:" + port + "/")).success());
            assertTrue(tool.waitFor("run-b", Map.of("selector", "#loaded", "timeoutMs", 5_000)).success());
            assertTrue(tool.type("run-b", Map.of("selector", "#task-title", "text", "Other run")).success());
            assertTrue(tool.click("run-b", Map.of("selector", "#submit")).success());
            assertTrue(tool.snapshot("run-b", Map.of()).result().get("textPreview").toString().contains("Saved Other run"));
            assertTrue(tool.snapshot("run-a", Map.of()).result().get("textPreview").toString().contains("Saved Browser E2E"));
            assertTrue(tool.closeSession("run-b"));
            assertFalse(tool.snapshot("run-b", Map.of()).success());
            assertTrue(tool.snapshot("run-a", Map.of()).result().get("textPreview").toString().contains("Saved Browser E2E"));
        } finally {
            server.stop(0);
        }
    }
}
