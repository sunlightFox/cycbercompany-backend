package io.github.yourname.agentstudio.nodeclient.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reports the current Windows privilege posture of the node process.
 *
 * <p>This is a read-only self-check, useful before attempting service, process, or installer
 * actions that depend on an elevated administrator token or a SYSTEM account.
 */
public final class PrivilegeTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 20;
    private static final int MAX_TIMEOUT_SECONDS = 30;
    private static final String QUERY_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
            $principal = [System.Security.Principal.WindowsPrincipal]::new($identity)
            $isLocalSystem = $identity.User -and $identity.User.Value -eq 'S-1-5-18'
            $isAdminToken = $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
            [pscustomobject]@{
              accountName = $identity.Name
              userSid = $identity.User.Value
              isLocalSystem = $isLocalSystem
              isAdministratorToken = $isAdminToken
              isPrivileged = ($isLocalSystem -or $isAdminToken)
              os = [System.Environment]::OSVersion.VersionString
            } | ConvertTo-Json -Compress
            """;

    private final CommandRunner runner;
    private final boolean windows;

    public PrivilegeTool() {
        this(new ProcessCommandRunner(), isWindows());
    }

    PrivilegeTool(CommandRunner runner, boolean windows) {
        this.runner = runner;
        this.windows = windows;
    }

    public ToolExecutionResult query() {
        if (!windows) {
            return ToolExecutionResult.failure("system.privilege.query is available on Windows only.");
        }
        try {
            CommandResult commandResult = runner.run(powershellCommand(QUERY_SCRIPT), DEFAULT_TIMEOUT_SECONDS);
            Map<String, Object> result = resultMap(commandResult);
            if (commandResult.timedOut()) {
                return ToolExecutionResult.failure(result, "Privilege query timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
            }
            if (commandResult.exitCode() != 0) {
                return ToolExecutionResult.failure(result, "Privilege query failed.");
            }
            try {
                Map<String, Object> privilege = OBJECT_MAPPER.readValue(
                        commandResult.stdout().text(), new TypeReference<>() {
                        });
                result.put("privilege", privilege);
                return ToolExecutionResult.success(result);
            } catch (IOException ex) {
                return ToolExecutionResult.failure(result, "Privilege query succeeded but returned an unreadable snapshot.");
            }
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start PowerShell for privilege query.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Privilege query was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read privilege query output.");
        }
    }

    private Map<String, Object> resultMap(CommandResult commandResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "query");
        result.put("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        result.put("durationMs", commandResult.durationMs());
        result.put("timedOut", commandResult.timedOut());
        result.put("exitCode", commandResult.timedOut() ? null : commandResult.exitCode());
        result.put("stdout", commandResult.stdout().text());
        result.put("stderr", commandResult.stderr().text());
        result.put("stdoutTruncated", commandResult.stdout().truncated());
        result.put("stderrTruncated", commandResult.stderr().truncated());
        result.put("outputTruncated", commandResult.stdout().truncated() || commandResult.stderr().truncated());
        result.put("limitations", "Reports the node process token only; it does not inspect other processes.");
        return result;
    }

    private static List<String> powershellCommand(String script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(List<String> command, int timeoutSeconds)
                throws IOException, InterruptedException, ExecutionException;
    }

    record CommandResult(
            int exitCode,
            CapturedOutput stdout,
            CapturedOutput stderr,
            boolean timedOut,
            long durationMs) {
    }

    record CapturedOutput(String text, boolean truncated) {
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, int timeoutSeconds)
                throws IOException, InterruptedException, ExecutionException {
            if (!isWindows()) {
                throw new IOException("Windows only");
            }
            long startedAt = System.nanoTime();
            Process process = new ProcessBuilder(command).start();
            try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<CapturedOutput> stdoutReader = readers.submit(() -> readOutput(process.getInputStream()));
                Future<CapturedOutput> stderrReader = readers.submit(() -> readOutput(process.getErrorStream()));
                boolean finished;
                try {
                    finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                    if (!finished) {
                        terminateProcessTree(process, false);
                        if (!process.waitFor(2, TimeUnit.SECONDS)) {
                            terminateProcessTree(process, true);
                            process.waitFor(2, TimeUnit.SECONDS);
                        }
                    }
                } catch (InterruptedException ex) {
                    terminateProcessTree(process, false);
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        terminateProcessTree(process, true);
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                    throw ex;
                }
                CapturedOutput stdout = stdoutReader.get();
                CapturedOutput stderr = stderrReader.get();
                long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                return new CommandResult(finished ? process.exitValue() : -1, stdout, stderr, !finished, durationMs);
            }
        }

        private static CapturedOutput readOutput(InputStream stream) throws IOException {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
            boolean truncated = false;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                int remaining = MAX_OUTPUT_BYTES - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(read, remaining));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
            String text = captured.toString(StandardCharsets.UTF_8);
            return new CapturedOutput(truncated ? text + "\n[output truncated]" : text, truncated);
        }

        private static void terminateProcessTree(Process process, boolean forcibly) {
            List<ProcessHandle> descendants = process.toHandle().descendants().toList();
            for (int index = descendants.size() - 1; index >= 0; index--) {
                if (forcibly) {
                    descendants.get(index).destroyForcibly();
                } else {
                    descendants.get(index).destroy();
                }
            }
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }
}
