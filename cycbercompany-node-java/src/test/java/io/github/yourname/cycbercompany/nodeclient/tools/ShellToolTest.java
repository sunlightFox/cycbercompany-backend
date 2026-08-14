package io.github.yourname.cycbercompany.nodeclient.tools;

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
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "echo cycbercompany", "cwd", ".", "timeoutSeconds", 5));

        assertTrue(result.success());
        assertTrue(result.result().get("stdout").toString().contains("cycbercompany"));
        assertEquals("workspace", result.result().get("workingDirectoryScope"));
        assertFalse(result.result().containsKey("command"));
        assertFalse(result.result().containsKey("cwd"));
        assertFalse(result.result().toString().contains(workspace.toRealPath().toString()));
    }

    @Test
    void redactsSensitiveJvmPropertiesFromCommandOutput() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-redaction");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "echo -Djavax.net.ssl.trustStorePassword=do-not-display",
                "timeoutSeconds", 5));

        assertTrue(result.success());
        assertTrue(result.result().get("stdout").toString()
                .contains("-Djavax.net.ssl.trustStorePassword=***"));
        assertFalse(result.result().get("stdout").toString().contains("do-not-display"));
    }

    @Test
    void rejectsWorkingDirectoryOutsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "echo should-not-run", "cwd", workspace.getParent().toString()));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("configured workspace"));
    }

    @Test
    void rejectsUnreplacedCwdPlaceholdersBeforeStartingShell() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-placeholder");
        ShellTool tool = new ShellTool(workspace, true);

        var result = tool.run(Map.of("command", "echo should-not-run", "cwd", "<path>"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("unreplaced placeholder"));
    }

    @Test
    void rejectsLikelyLongRunningDevServersBeforeStartingShell() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-dev-server");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "npm run dev", "timeoutSeconds", 120));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("long-running development server"));
        assertTrue(result.errorMessage().contains("process.start"));
    }

    @Test
    void rejectsBuildOutputPipelinesThatCanLeaveGradleRunning() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-build-pipeline");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "./gradlew tasks --quiet | head -80", "timeoutSeconds", 120));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Do not pipe Gradle or Maven output"));
    }

    @Test
    void rejectsCommandsThatMaskTheirOwnExitStatus() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-exit-mask");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "curl -ksS --fail https://127.0.0.1:1; echo EXIT=$?"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("masks the command exit status"));
    }

    @Test
    void rejectsCommonExecWrappedDevServersBeforeStartingShell() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-dev-server");
        ShellTool tool = new ShellTool(workspace);

        assertFalse(tool.run(Map.of("command", "npx vite preview", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "npm exec vite dev", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "pnpm exec next dev", "timeoutSeconds", 120)).success());
    }

    @Test
    void rejectsWindowsCommandShimDevServersBeforeStartingShell() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-dev-server");
        ShellTool tool = new ShellTool(workspace);

        assertFalse(tool.run(Map.of("command", "npm.cmd run dev", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "npx.cmd vite preview", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "pnpm.exe exec next dev", "timeoutSeconds", 120)).success());
    }

    @Test
    void rejectsCommonNodeWatcherWrappersBeforeStartingShell() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-dev-server");
        ShellTool tool = new ShellTool(workspace);

        assertFalse(tool.run(Map.of("command", "npm run preview", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "npm run watch", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "npm run storybook", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "nodemon server.js", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "ts-node-dev src/index.ts", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "python -m gunicorn app:app", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "python -m flask run", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "docker compose up", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of(
                "command", "docker compose -f deploy/docker-compose.yml up --build",
                "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "docker-compose up", "timeoutSeconds", 120)).success());
    }

    @Test
    void rejectsCmdStartWrappersBeforeStartingShell() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-dev-server");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "cmd /c start npm run dev", "timeoutSeconds", 120));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("long-running development server"));
        assertTrue(result.errorMessage().contains("process.start"));
    }

    @Test
    void rejectsCommonShellBackgroundingWrappersBeforeStartingShell() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-dev-server");
        ShellTool tool = new ShellTool(workspace);

        assertFalse(tool.run(Map.of("command", "Start-Job { npm run dev }", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "Start-Process npm -ArgumentList 'run dev'", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "nohup python app.py", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "python app.py &", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "disown npm run dev", "timeoutSeconds", 120)).success());
        assertFalse(tool.run(Map.of("command", "setsid npm run dev", "timeoutSeconds", 120)).success());
    }

    @Test
    void systemAccessAcceptsAWorkingDirectoryOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        Path outside = Files.createTempDirectory("cycbercompany-node-system-shell");
        ShellTool tool = new ShellTool(workspace, true);

        var result = tool.run(Map.of("command", "echo system-access", "cwd", outside.toString(), "timeoutSeconds", 5));

        assertTrue(result.success());
        assertEquals("system", result.result().get("workingDirectoryScope"));
        assertFalse(result.result().toString().contains(outside.toRealPath().toString()));
    }

    @Test
    void acceptsDetachedDockerComposeAsShortLivedShellCommand() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell-compose");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "docker compose up -d --build", "timeoutSeconds", 5));

        assertFalse(result.errorMessage() != null
                && result.errorMessage().contains("long-running development server"));
        var withFile = tool.run(Map.of(
                "command", "docker compose -f deploy/docker-compose.yml up -d --build",
                "timeoutSeconds", 5));

        assertFalse(withFile.errorMessage() != null
                && withFile.errorMessage().contains("long-running development server"));
    }

    @Test
    void preservesQuotedArgumentsWhenRunningWindowsShellCommands() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "powershell -NoProfile -Command \"Write-Output quoted-shell-test\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertTrue(result.result().get("stdout").toString().contains("quoted-shell-test"));
    }

    @Test
    void preservesQuotedPowerShellScriptArguments() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "powershell -NoProfile -Command \"Start-Sleep -Seconds 1\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertEquals(0, result.result().get("exitCode"));
    }

    @Test
    void handlesJsonEscapedQuotesThatMayArriveFromModelToolArguments() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "powershell -NoProfile -Command \\\"Write-Output escaped-shell-test\\\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertTrue(result.result().get("stdout").toString().contains("escaped-shell-test"));
    }

    @Test
    void handlesACommandWrappedInAnOuterQuote() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of(
                "command", "\"powershell -NoProfile -Command \\\"Write-Output outer-shell-test\\\"\"",
                "timeoutSeconds", 5));

        assertTrue(result.success(), () -> "Unexpected shell failure: " + result.errorMessage());
        assertTrue(result.result().get("stdout").toString().contains("outer-shell-test"));
    }

    @Test
    void interruptsAndTerminatesLongRunningCommands() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-shell");
        ShellTool tool = new ShellTool(workspace);
        AtomicReference<io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult> result = new AtomicReference<>();
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
