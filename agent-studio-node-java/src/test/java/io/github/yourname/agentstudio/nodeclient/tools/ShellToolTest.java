package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ShellToolTest {

    @Test
    void executesCommandInsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "echo agent-studio", "cwd", ".", "timeoutSeconds", 5));

        assertTrue(result.success());
        assertTrue(result.result().get("stdout").toString().contains("agent-studio"));
        assertEquals("workspace", result.result().get("workingDirectoryScope"));
        assertFalse(result.result().containsKey("command"));
        assertFalse(result.result().containsKey("cwd"));
        assertFalse(result.result().toString().contains(workspace.toRealPath().toString()));
    }

    @Test
    void rejectsWorkingDirectoryOutsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "echo should-not-run", "cwd", workspace.getParent().toString()));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("configured workspace"));
    }

    @Test
    void systemAccessAcceptsAWorkingDirectoryOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        Path outside = Files.createTempDirectory("agent-studio-node-system-shell");
        ShellTool tool = new ShellTool(workspace, true);

        var result = tool.run(Map.of("command", "echo system-access", "cwd", outside.toString(), "timeoutSeconds", 5));

        assertTrue(result.success());
        assertEquals("system", result.result().get("workingDirectoryScope"));
        assertFalse(result.result().toString().contains(outside.toRealPath().toString()));
    }

    @Test
    void preservesQuotedArgumentsWhenRunningWindowsShellCommands() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "powershell -NoProfile -Command \"Write-Output quoted-shell-test\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertTrue(result.result().get("stdout").toString().contains("quoted-shell-test"));
    }

    @Test
    void preservesQuotedPowerShellScriptArguments() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "powershell -NoProfile -Command \"Start-Sleep -Seconds 1\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertEquals(0, result.result().get("exitCode"));
    }

    @Test
    void handlesJsonEscapedQuotesThatMayArriveFromModelToolArguments() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "powershell -NoProfile -Command \\\"Write-Output escaped-shell-test\\\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertTrue(result.result().get("stdout").toString().contains("escaped-shell-test"));
    }

    @Test
    void handlesACommandWrappedInAnOuterQuote() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "\"powershell -NoProfile -Command \\\"Write-Output outer-shell-test\\\"\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertTrue(result.result().get("stdout").toString().contains("outer-shell-test"));
    }

    @Test
    void interruptsAndTerminatesLongRunningCommands() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);
        AtomicReference<io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(tool.run(Map.of(
                "command", "powershell -NoProfile -Command Start-Sleep -Seconds 30",
                "timeoutSeconds", 120))));

        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "Interrupted shell execution must return promptly.");
        assertFalse(result.get().success());
        assertTrue(result.get().errorMessage().contains("interrupted"));
    }
}
