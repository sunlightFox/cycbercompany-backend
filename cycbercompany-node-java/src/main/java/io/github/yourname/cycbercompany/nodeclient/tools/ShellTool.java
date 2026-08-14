package io.github.yourname.cycbercompany.nodeclient.tools;

import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
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
import java.util.regex.Pattern;

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
    private static final Pattern SENSITIVE_ENVIRONMENT_VALUE = Pattern.compile(
            "(?i)(-D[a-z0-9_.-]*?(?:api[_-]?key|token|secret|password|authorization)[a-z0-9_.-]*=)([^\\s,;]+)");
    private static final Pattern BUILD_OUTPUT_PIPE = Pattern.compile(
            "(?i)\\b(?:gradle|gradlew|mvn|mvnw)(?:\\.bat|\\.cmd)?\\b[^\\r\\n|]*\\|\\s*(?:head|tail)\\b");
    private static final Pattern EXIT_STATUS_MASKING = Pattern.compile(
            "(?i)(?:;|&&|\\|\\|)\\s*(?:echo|printf)\\s+[^\\r\\n]*(?:exit|status|code)\\s*=\\s*\\$\\?");
    private static final List<Pattern> LIKELY_LONG_RUNNING_SERVER_COMMANDS = List.of(
            Pattern.compile("\\b(?:npm|pnpm|yarn|bun)(?:\\.cmd|\\.exe)?\\s+(?:run\\s+)?(?:dev|serve|start|preview|watch|storybook)\\b"),
            Pattern.compile("\\b(?:npm|pnpm|yarn|bun)(?:\\.cmd|\\.exe)?\\s+exec\\s+.*\\b(?:dev|serve|start|preview|watch)\\b"),
            Pattern.compile("\\b(?:(?:npx|bunx)(?:\\.cmd|\\.exe)?|npm(?:\\.cmd|\\.exe)?\\s+exec|pnpm(?:\\.cmd|\\.exe)?\\s+exec|yarn(?:\\.cmd|\\.exe)?\\s+dlx)\\s+(?:vite|next|nuxt|parcel|webpack-dev-server|http-server|live-server|storybook|nodemon|ts-node-dev)\\b"),
            Pattern.compile("\\b(?:vite|next|nuxt)\\s+(?:dev|preview)\\b"),
            Pattern.compile("\\b(?:ng|nx)\\s+serve\\b"),
            Pattern.compile("\\breact-scripts\\s+start\\b"),
            Pattern.compile("\\bpython(?:\\.exe)?\\s+-m\\s+http\\.server\\b"),
            Pattern.compile("\\bpython(?:\\.exe)?\\s+-m\\s+(?:uvicorn|gunicorn|hypercorn|flask)\\b"),
            Pattern.compile("\\b(?:uvicorn|gunicorn|hypercorn)\\b"),
            Pattern.compile("\\b(?:flask\\s+run|nodemon|ts-node-dev|vite-node)\\b"),
            Pattern.compile("\\bdotnet\\s+watch\\b"),
            Pattern.compile("\\b(?:gradle|gradlew|gradlew\\.bat|mvn|mvnw|mvnw\\.cmd)\\s+.*\\b(?:bootRun|spring-boot:run)\\b"));

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
        if (looksLikeLongRunningServerCommand(command)) {
            return ToolExecutionResult.failure(
                    "This command looks like a long-running development server or watch process. "
                            + "Use process.start or system.process.start when advertised; shell.run is for short-lived commands.");
        }
        if (BUILD_OUTPUT_PIPE.matcher(command).find()) {
            return ToolExecutionResult.failure(
                    "Do not pipe Gradle or Maven output through head/tail. shell.run already bounds output; "
                            + "run the command with --no-daemon --console=plain instead.");
        }
        if (EXIT_STATUS_MASKING.matcher(command).find()) {
            return ToolExecutionResult.failure(
                    "Do not append echo/printf of $? to a command. It masks the command exit status; run the command directly so shell.run can report success or failure.");
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
                    // Future.cancel(true) is how the node transport interrupts a running invocation.
                    // Do not leave cmd.exe or its descendants running after the caller has stopped the run.
                    terminateProcessTree(process, false);
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        terminateProcessTree(process, true);
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                    throw ex;
                }
                stdout = stdoutReader.get();
                stderr = stderrReader.get();
            }

            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            Map<String, Object> result = new LinkedHashMap<>();
            // 命令和绝对目录已经是调用参数，重复返回只会把本机环境细节带进模型上下文。
            // systemAccess 场景也只说明执行范围，绝不披露工作区外目录。
            result.put("workingDirectoryScope", cwd.startsWith(workspaceRoot) ? "workspace" : "system");
            result.put("durationMs", durationMs);
            result.put("timedOut", !finished);
            result.put("stdout", redactSensitiveOutput(stdout.text()));
            result.put("stderr", redactSensitiveOutput(stderr.text()));
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
            return ToolExecutionResult.failure("Failed to start command.");
        } catch (RuntimeException ex) {
            return ToolExecutionResult.failure("shell.run failed before completion.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Command execution was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read command output.");
        }
    }

    private Path resolveCwd(String requestedCwd) {
        if (requestedCwd != null && !requestedCwd.isBlank()) {
            rejectPlaceholderPath(requestedCwd, "cwd");
        }
        Path candidate = requestedCwd == null || requestedCwd.isBlank()
                ? workspaceRoot
                : Path.of(requestedCwd);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("Working directory does not exist or is inaccessible.");
        }
        try {
            Path realPath = candidate.toRealPath();
            if (!systemAccess && !realPath.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("Working directory must stay inside the configured workspace.");
            }
            return realPath;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve working directory.", ex);
        }
    }

    private static String redactSensitiveOutput(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return SENSITIVE_ENVIRONMENT_VALUE.matcher(value).replaceAll("$1***");
    }

    private static void rejectPlaceholderPath(String requested, String argumentName) {
        String normalized = requested.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> placeholders = List.of(
                "<path>",
                "<absolute path>",
                "<folder>",
                "<directory>",
                "<dir>",
                "<cwd>",
                "<workspace>",
                "<project root>",
                "<desktop>",
                "<desktoppath>",
                "<desktop path>");
        if (placeholders.stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException(argumentName
                    + " contains an unreplaced placeholder. Use a concrete working directory returned by an inspection tool or provided by the user, or omit cwd.");
        }
    }

    private static boolean looksLikeLongRunningServerCommand(String command) {
        String normalized = command == null ? "" : command.toLowerCase(Locale.ROOT)
                .replaceAll("[\"']", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("start-job")
                || normalized.contains("start-process")
                || normalized.contains("disown")
                || normalized.contains("setsid")
                || normalized.contains("nohup ")
                || normalized.endsWith(" &")
                || normalized.matches("^(?:cmd(?:\\.exe)?\\s+/c\\s+)?start(?:\\s+|$).*")
                || dockerComposeUpWithoutDetach(normalized)
                || LIKELY_LONG_RUNNING_SERVER_COMMANDS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    private static boolean dockerComposeUpWithoutDetach(String normalized) {
        boolean composeUp = normalized.matches(".*\\bdocker(?:\\.exe)?\\s+compose\\b.*\\sup\\b.*")
                || normalized.matches(".*\\bdocker-compose(?:\\.exe)?\\b.*\\sup\\b.*");
        return composeUp && !normalized.matches(".*(?:^|\\s)(?:-d|--detach)(?:\\s|$).*");
    }

    private static List<String> shellCommand(String command) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win")
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    /**
     * Describes the shell grammar that receives the command argument. This is
     * advertised with the capability because a generic "platform shell" label
     * causes models to mix CMD, PowerShell, and POSIX syntax.
     */
    public static String commandDialectDescription() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "Commands run through cmd.exe /d /s /c. Use CMD syntax such as dir and &&; do not use POSIX "
                    + "syntax such as ls, ';', $?, xxd, or PowerShell variables unless invoking PowerShell explicitly.";
        }
        return "Commands run through /bin/sh -lc. Use POSIX shell syntax.";
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

    private record CapturedOutput(String text, boolean truncated) {
    }
}
