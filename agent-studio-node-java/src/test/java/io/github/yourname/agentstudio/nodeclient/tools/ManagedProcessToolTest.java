package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static String longRunningCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "ping -n 20 127.0.0.1 > nul"
                : "sleep 20";
    }
}
