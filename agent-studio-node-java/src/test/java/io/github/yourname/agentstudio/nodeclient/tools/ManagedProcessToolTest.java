package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedProcessToolTest {

    @Test
    void startsInspectsAndStopsAManagedProcess() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-managed-process");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var started = tool.start(Map.of("command", longRunningCommand()));

            assertTrue(started.success());
            String processId = started.result().get("processId").toString();
            assertTrue(Boolean.TRUE.equals(started.result().get("active")));
            assertTrue(started.result().get("stdoutPath").toString().startsWith(workspace.toRealPath().toString()));

            var status = tool.status(Map.of("processId", processId));
            assertTrue(status.success());
            assertTrue(Boolean.TRUE.equals(status.result().get("active")));

            var stopped = tool.stop(Map.of("processId", processId));
            assertTrue(stopped.success());
            assertTrue(Boolean.TRUE.equals(stopped.result().get("stopped")));

            var afterStop = tool.status(Map.of("processId", processId));
            assertTrue(afterStop.success());
            assertFalse(Boolean.TRUE.equals(afterStop.result().get("active")));
        }
    }

    @Test
    void rejectsCommandsThatDetachFromTheManagedHandle() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-managed-process");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var result = tool.start(Map.of("command", "powershell Start-Process java"));

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("manages the command"));
        }
    }

    @Test
    void readsOnlyTheSelectedManagedStreamAndBoundsTheReturnedTail() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-managed-process-logs");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var started = tool.start(Map.of("command", outputCommand()));

            assertTrue(started.success());
            String processId = started.result().get("processId").toString();
            var stdout = awaitLog(tool, processId, "stdout", "stdout-message");
            var stderr = awaitLog(tool, processId, "stderr", "stderr-message");

            assertTrue(stdout.success());
            assertTrue(stdout.result().get("content").toString().contains("stdout-message"));
            assertTrue(stderr.success());
            assertTrue(stderr.result().get("content").toString().contains("stderr-message"));
            assertEquals("stderr", stderr.result().get("stream"));
            assertFalse(stdout.result().get("content").toString().contains("stderr-message"));
            assertFalse(tool.logs(Map.of("processId", processId, "stream", "stdin")).success());
            assertFalse(tool.logs(Map.of("processId", processId, "maxChars", 32_001)).success());
        }
    }

    @Test
    void waitsForManagedLocalHttpHealthWithoutReturningTheResponseBody() throws Exception {
        // 使用真实的 JDK HTTP Server，覆盖前后端联调中“进程已启动但端口尚未可用”的就绪判断路径。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            // 故意提供一个不应出现在工具结果中的正文，验证 wait_http 只保留状态码等元数据。
            byte[] body = "private-health-response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path workspace = Files.createTempDirectory("agent-studio-managed-process-http");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var started = tool.start(Map.of("command", longRunningCommand()));
            assertTrue(started.success());

            String processId = started.result().get("processId").toString();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
            var ready = tool.waitHttp(Map.of("processId", processId, "url", url, "expectedStatus", 201));

            assertTrue(ready.success());
            assertEquals(processId, ready.result().get("processId"));
            assertEquals(url, ready.result().get("url"));
            assertEquals(201, ready.result().get("statusCode"));
            assertTrue(Boolean.TRUE.equals(ready.result().get("ready")));
            assertFalse(ready.result().toString().contains("private-health-response"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRemoteAndCredentialBearingHttpReadinessUrls() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-managed-process-http-policy");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var started = tool.start(Map.of("command", longRunningCommand()));
            assertTrue(started.success());
            String processId = started.result().get("processId").toString();

            var remote = tool.waitHttp(Map.of(
                    "processId", processId, "url", "http://example.com/health", "timeoutMs", 100));
            var query = tool.waitHttp(Map.of(
                    "processId", processId, "url", "http://127.0.0.1:8080/health?token=private", "timeoutMs", 100));
            var unknownProcess = tool.waitHttp(Map.of(
                    "processId", "proc_unknown", "url", "http://127.0.0.1:8080/health", "timeoutMs", 100));

            assertFalse(remote.success());
            assertTrue(remote.errorMessage().contains("only localhost"));
            assertFalse(query.success());
            assertTrue(query.errorMessage().contains("must not contain credentials"));
            assertFalse(query.errorMessage().contains("private"));
            assertFalse(unknownProcess.success());
            assertTrue(unknownProcess.errorMessage().contains("Unknown managed process"));
        }
    }

    @Test
    void reportsAStatusMismatchAsUnreadyWithoutExposingTheBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        Path workspace = Files.createTempDirectory("agent-studio-managed-process-http-status");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var started = tool.start(Map.of("command", longRunningCommand()));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
            var result = tool.waitHttp(Map.of(
                    "processId", started.result().get("processId"), "url", url,
                    "expectedStatus", 200, "timeoutMs", 100));

            assertFalse(result.success());
            assertEquals(204, result.result().get("statusCode"));
            assertFalse(Boolean.TRUE.equals(result.result().get("ready")));
            assertTrue(Boolean.TRUE.equals(result.result().get("processActive")));
        } finally {
            server.stop(0);
        }
    }

    private static io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult awaitLog(
            ManagedProcessTool tool, String processId, String stream, String expected) throws Exception {
        io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult latest = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            latest = tool.logs(Map.of("processId", processId, "stream", stream, "maxChars", 64));
            if (latest.success() && latest.result().get("content").toString().contains(expected)) {
                return latest;
            }
            Thread.sleep(50);
        }
        return latest;
    }

    private static String outputCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "echo stdout-message & echo stderr-message 1>&2"
                : "printf 'stdout-message\\n'; printf 'stderr-message\\n' >&2";
    }

    private static String longRunningCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "ping -n 20 127.0.0.1 > nul"
                : "sleep 20";
    }
}
