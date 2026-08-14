package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedProcessToolTest {

    @Test
    void startsInspectsAndStopsAManagedProcess() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var started = tool.start(Map.of("command", longRunningCommand()));

            assertTrue(started.success());
            String processId = started.result().get("processId").toString();
            assertTrue(Boolean.TRUE.equals(started.result().get("active")));
            assertEquals(".", started.result().get("workingDirectory"));
            assertFalse(started.result().containsKey("command"));
            assertFalse(started.result().containsKey("rootPid"));
            assertFalse(started.result().containsKey("stdoutPath"));

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
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var result = tool.start(Map.of("command", "powershell Start-Process java"));

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("manages the command"));
        }
    }

    @Test
    void rejectsCmdStartWrappersThatDetachFromTheManagedHandle() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var result = tool.start(Map.of("command", "cmd /c start npm run dev"));

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("manages the command"));
        }
    }

    @Test
    void rejectsCommonDetachedProcessWrappers() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            assertFalse(tool.start(Map.of("command", "Start-Job { npm run dev }")).success());
            assertFalse(tool.start(Map.of("command", "disown npm run dev")).success());
            assertFalse(tool.start(Map.of("command", "setsid npm run dev")).success());
        }
    }

    @Test
    void rejectsUnreplacedPathPlaceholdersBeforeStartingAProcess() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-placeholder");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace, true)) {
            var cwd = tool.start(Map.of("command", longRunningCommand(), "cwd", "<path>"));
            var stdout = tool.start(Map.of("command", longRunningCommand(), "stdoutPath", "<absolute path>"));

            assertFalse(cwd.success());
            assertTrue(cwd.errorMessage().contains("unreplaced placeholder"));
            assertFalse(stdout.success());
            assertTrue(stdout.errorMessage().contains("unreplaced placeholder"));
        }
    }

    @Test
    void rejectsLogPathsEscapingWorkspaceThroughDirectorySymlinks() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-log-symlink");
        Path outside = Files.createTempDirectory("cycbercompany-managed-process-log-outside");
        Path link = workspace.resolve("outside-logs");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment.");
        }
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var result = tool.start(Map.of(
                    "command", longRunningCommand(),
                    "stdoutPath", link.resolve("server.log").toString()));

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("configured workspace"));
        }
    }

    @Test
    void rejectsDefaultLogDirectoryWhenCycberCompanyFolderEscapesThroughSymlink() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-default-log-symlink");
        Path outside = Files.createTempDirectory("cycbercompany-managed-process-default-log-outside");
        try {
            Files.createSymbolicLink(workspace.resolve(".cycbercompany"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment.");
        }
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace)) {
            var result = tool.start(Map.of("command", longRunningCommand()));

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("configured workspace"));
        }
    }

    @Test
    void reportsAnUnreadableWorkingDirectoryWithoutEchoingThePath() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-invalid-cwd");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace, true)) {
            var result = tool.start(Map.of("command", longRunningCommand(), "cwd", workspace.resolve("missing").toString()));

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("does not exist or is inaccessible"));
            assertFalse(result.errorMessage().contains("missing"));
        }
    }

    @Test
    void systemAccessCanStartProcessesOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-system");
        Path outside = Files.createTempDirectory("cycbercompany-managed-process-system-outside");
        try (ManagedProcessTool tool = new ManagedProcessTool(workspace, true)) {
            var started = tool.start(Map.of(
                    "command", longRunningCommand(),
                    "cwd", outside.toString()));

            assertTrue(started.success());
            assertEquals("system", started.result().get("workingDirectoryScope"));
            assertFalse(started.result().containsKey("workingDirectory"));
            var stopped = tool.stop(Map.of("processId", started.result().get("processId")));
            assertTrue(stopped.success());
        }
    }

    @Test
    void readsOnlyTheSelectedManagedStreamAndBoundsTheReturnedTail() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-logs");
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
            assertFalse(stdout.result().containsKey("path"));
            assertFalse(stderr.result().containsKey("path"));
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
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-http");
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
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-http-policy");
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
        Path workspace = Files.createTempDirectory("cycbercompany-managed-process-http-status");
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

    private static io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult awaitLog(
            ManagedProcessTool tool, String processId, String stream, String expected) throws Exception {
        io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult latest = null;
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
