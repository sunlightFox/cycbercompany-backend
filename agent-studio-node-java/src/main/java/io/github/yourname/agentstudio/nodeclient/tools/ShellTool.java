package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Executes a command from a configured node workspace.
 *
 * <p>This tool deliberately runs through the platform shell so normal development commands such as
 * {@code gradlew test}, {@code git status}, and shell built-ins work consistently. It is therefore a
 * high-risk capability: the server must keep it behind explicit approval or an administrator-set policy.
 */
public final class ShellTool {

    private static final int MAX_COMMAND_CHARS = 8_000;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;

    private final Path workspaceRoot;
    private final boolean systemAccess;

    public ShellTool(Path workspaceRoot) {
        this(workspaceRoot, false);
    }

    public ShellTool(Path workspaceRoot, boolean systemAccess) {
        try {
            if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
                throw new IllegalArgumentException("Workspace must be an existing directory: " + workspaceRoot);
            }
            this.workspaceRoot = workspaceRoot.toRealPath();
            this.systemAccess = systemAccess;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve workspace: " + workspaceRoot, ex);
        }
    }

    public ToolExecutionResult run(Map<String, Object> arguments) {
        String command = stringValue(arguments, "command");
        if (command == null || command.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: command");
        }
        if (command.length() > MAX_COMMAND_CHARS) {
            return ToolExecutionResult.failure("Command exceeds the " + MAX_COMMAND_CHARS + " character limit.");
        }

        try {
            Path cwd = resolveCwd(stringValue(arguments, "cwd"));
            int timeoutSeconds = timeoutSeconds(arguments);
            long startedAt = System.nanoTime();
            Process process = new ProcessBuilder(shellCommand(command))
                    .directory(cwd.toFile())
                    .start();

            CapturedOutput stdout;
            CapturedOutput stderr;
            boolean finished;
            try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<CapturedOutput> stdoutReader = readers.submit(() -> readOutput(process.getInputStream()));
                Future<CapturedOutput> stderrReader = readers.submit(() -> readOutput(process.getErrorStream()));
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    terminateProcessTree(process, false);
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        terminateProcessTree(process, true);
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                }
                stdout = stdoutReader.get();
                stderr = stderrReader.get();
            }

            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", command);
            result.put("cwd", cwd.toString());
            result.put("durationMs", durationMs);
            result.put("timedOut", !finished);
            result.put("stdout", stdout.text());
            result.put("stderr", stderr.text());
            result.put("stdoutTruncated", stdout.truncated());
            result.put("stderrTruncated", stderr.truncated());
            result.put("outputTruncated", stdout.truncated() || stderr.truncated());

            if (!finished) {
                result.put("exitCode", null);
                return ToolExecutionResult.failure(result, "Command timed out after " + timeoutSeconds + " seconds.");
            }

            int exitCode = process.exitValue();
            result.put("exitCode", exitCode);
            if (exitCode != 0) {
                return ToolExecutionResult.failure(result, "Command exited with code " + exitCode + ".");
            }
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start command: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Command execution was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read command output: " + safeMessage(ex.getCause()));
        }
    }

    private Path resolveCwd(String requestedCwd) {
        Path candidate = requestedCwd == null || requestedCwd.isBlank()
                ? workspaceRoot
                : Path.of(requestedCwd);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("Working directory does not exist: " + candidate);
        }
        try {
            Path realPath = candidate.toRealPath();
            if (!systemAccess && !realPath.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("Working directory must stay inside the configured workspace.");
            }
            return realPath;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve working directory: " + candidate, ex);
        }
    }

    private static List<String> shellCommand(String command) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win")
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
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

    private static int timeoutSeconds(Map<String, Object> arguments) {
        String value = stringValue(arguments, "timeoutSeconds");
        if (value == null || value.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(1, Math.min(parsed, MAX_TIMEOUT_SECONDS));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("timeoutSeconds must be an integer.");
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

    private static String stringValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }

    private record CapturedOutput(String text, boolean truncated) {
    }
}
