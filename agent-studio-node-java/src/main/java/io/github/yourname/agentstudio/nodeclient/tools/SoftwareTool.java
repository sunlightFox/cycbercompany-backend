package io.github.yourname.agentstudio.nodeclient.tools;

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
 * Structured Windows software operations backed by winget.
 *
 * <p>This tool intentionally accepts package identifiers, not command lines or display names. It
 * gives the model a narrow install-state/uninstall capability without exposing another shell.
 */
public final class SoftwareTool {

    private static final int WINGET_NO_APPLICATIONS_FOUND = 0x8A150014;
    private static final int HTTP_STATUS_NOT_FOUND = 0x80190194;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_INSTALL_TIMEOUT_SECONDS = 600;
    private static final int DEFAULT_UNINSTALL_TIMEOUT_SECONDS = 300;
    private static final int MAX_STANDARD_TIMEOUT_SECONDS = 600;
    private static final int MAX_INSTALL_TIMEOUT_SECONDS = 1_200;

    private final CommandRunner runner;
    private final boolean windows;

    public SoftwareTool() {
        this(new ProcessCommandRunner(), isWindows());
    }

    SoftwareTool(CommandRunner runner, boolean windows) {
        this.runner = runner;
        this.windows = windows;
    }

    public ToolExecutionResult query(Map<String, Object> arguments) {
        try {
            PackageRequest request = request(arguments, DEFAULT_QUERY_TIMEOUT_SECONDS, MAX_STANDARD_TIMEOUT_SECONDS);
            List<String> command = List.of(
                    "winget",
                    "list",
                    "--id",
                    request.packageId(),
                    "--exact",
                    "--accept-source-agreements",
                    "--disable-interactivity");
            CommandResult commandResult = runner.run(command, request.timeoutSeconds());
            Map<String, Object> result = baseResult("query", request, commandResult);
            boolean exactPackageIdPresent = containsExactPackageIdToken(commandResult.stdout().text(), request.packageId());
            result.put("exactPackageIdPresent", exactPackageIdPresent);
            result.put("installed", commandResult.exitCode() == 0 && exactPackageIdPresent);
            if (commandResult.timedOut()) {
                return ToolExecutionResult.failure(result,
                        "Software query timed out after " + request.timeoutSeconds() + " seconds.");
            }
            if (commandResult.exitCode() == WINGET_NO_APPLICATIONS_FOUND) {
                result.put("installed", false);
                result.put("queryStatus", "NOT_INSTALLED");
                return ToolExecutionResult.success(result);
            }
            if (commandResult.exitCode() != 0) {
                return ToolExecutionResult.failure(result,
                        "winget list exited with code " + formatExitCode(commandResult.exitCode()) + ".");
            }
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start winget. Ensure Windows Package Manager is installed and available on PATH.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Software query was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read winget output.");
        }
    }

    public ToolExecutionResult install(Map<String, Object> arguments) {
        try {
            PackageRequest request = request(arguments, DEFAULT_INSTALL_TIMEOUT_SECONDS, MAX_INSTALL_TIMEOUT_SECONDS);
            boolean silent = booleanValue(arguments, "silent", true);
            boolean allowUpgrade = booleanValue(arguments, "allowUpgrade", false);
            String source = optionalSource(arguments);
            String scope = optionalScope(arguments);
            String version = optionalVersion(arguments);
            List<String> command = installCommand(request.packageId(), silent, allowUpgrade, source, scope, version);
            CommandResult commandResult = runner.run(command, request.timeoutSeconds());
            Map<String, Object> result = baseResult("install", request, commandResult);
            result.put("silent", silent);
            result.put("allowUpgrade", allowUpgrade);
            if (source != null) {
                result.put("source", source);
            }
            if (scope != null) {
                result.put("scope", scope);
            }
            if (version != null) {
                result.put("version", version);
            }
            if (commandResult.timedOut()) {
                return ToolExecutionResult.failure(result,
                        "Software install timed out after " + request.timeoutSeconds() + " seconds.");
            }
            if (commandResult.exitCode() != 0) {
                if (commandResult.exitCode() == HTTP_STATUS_NOT_FOUND) {
                    result.put("failureCategory", "PACKAGE_DOWNLOAD_NOT_FOUND");
                    String replacementPackageId = replacementPackageId(request.packageId());
                    if (replacementPackageId != null) {
                        result.put("suggestedPackageId", replacementPackageId);
                    }
                    return ToolExecutionResult.failure(result,
                            "winget could not download " + request.packageId()
                                    + " because the package URL returned HTTP 404 (0x80190194). "
                                    + "The winget manifest is likely stale; refresh the source or select another published version. "
                                    + (replacementPackageId == null ? "" : "The current replacement package is "
                                            + replacementPackageId + "; retry with that exact packageId. ")
                                    + "This is not an elevation or interactive-installer failure.");
                }
                return ToolExecutionResult.failure(result,
                        "winget install exited with code " + formatExitCode(commandResult.exitCode())
                                + ". The package may require an interactive vendor installer, an elevated/admin node, "
                                + "a trusted source agreement, network access, or a reboot.");
            }
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start winget. Ensure Windows Package Manager is installed and available on PATH.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Software install was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read winget output.");
        }
    }

    public ToolExecutionResult uninstall(Map<String, Object> arguments) {
        try {
            PackageRequest request = request(arguments, DEFAULT_UNINSTALL_TIMEOUT_SECONDS, MAX_STANDARD_TIMEOUT_SECONDS);
            boolean silent = booleanValue(arguments, "silent", true);
            List<String> command = uninstallCommand(request.packageId(), silent);
            CommandResult commandResult = runner.run(command, request.timeoutSeconds());
            Map<String, Object> result = baseResult("uninstall", request, commandResult);
            result.put("silent", silent);
            if (commandResult.timedOut()) {
                return ToolExecutionResult.failure(result,
                        "Software uninstall timed out after " + request.timeoutSeconds() + " seconds.");
            }
            if (commandResult.exitCode() != 0) {
                return ToolExecutionResult.failure(result,
                        "winget uninstall exited with code " + formatExitCode(commandResult.exitCode())
                                + ". The package may require a vendor UI, an elevated/admin node, a reboot, "
                                + "or a protected service or process may still be running. "
                                + "Run system.uninstall.preflight first to collect privilege, package, service, and process blockers.");
            }
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start winget. Ensure Windows Package Manager is installed and available on PATH.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Software uninstall was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read winget output.");
        }
    }

    private static List<String> installCommand(
            String packageId,
            boolean silent,
            boolean allowUpgrade,
            String source,
            String scope,
            String version) {
        List<String> command = new java.util.ArrayList<>();
        command.add("winget");
        command.add("install");
        command.add("--id");
        command.add(packageId);
        command.add("--exact");
        if (source != null) {
            command.add("--source");
            command.add(source);
        }
        if (scope != null) {
            command.add("--scope");
            command.add(scope);
        }
        if (version != null) {
            command.add("--version");
            command.add(version);
        }
        if (silent) {
            command.add("--silent");
        }
        if (!allowUpgrade) {
            command.add("--no-upgrade");
        }
        command.add("--accept-package-agreements");
        command.add("--accept-source-agreements");
        command.add("--disable-interactivity");
        return List.copyOf(command);
    }

    private static List<String> uninstallCommand(String packageId, boolean silent) {
        if (silent) {
            return List.of(
                    "winget",
                    "uninstall",
                    "--id",
                    packageId,
                    "--exact",
                    "--silent",
                    "--accept-source-agreements",
                    "--disable-interactivity");
        }
        return List.of(
                "winget",
                "uninstall",
                "--id",
                packageId,
                "--exact",
                "--accept-source-agreements",
                "--disable-interactivity");
    }

    private PackageRequest request(Map<String, Object> arguments, int defaultTimeoutSeconds, int maxTimeoutSeconds) {
        if (!windows) {
            throw new IllegalArgumentException("system.software currently supports Windows winget only.");
        }
        String manager = value(arguments, "manager");
        if (manager != null && !manager.isBlank() && !"winget".equalsIgnoreCase(manager.trim())) {
            throw new IllegalArgumentException("manager must be 'winget'.");
        }
        String packageId = WindowsToolArgumentPolicy.requireWingetPackageId(value(arguments, "packageId"));
        return new PackageRequest(packageId, "winget", boundedTimeout(
                value(arguments, "timeoutSeconds"), defaultTimeoutSeconds, maxTimeoutSeconds));
    }

    private static String optionalSource(Map<String, Object> arguments) {
        String source = value(arguments, "source");
        if (source == null || source.isBlank()) {
            return null;
        }
        source = source.trim();
        if (!"winget".equalsIgnoreCase(source) && !"msstore".equalsIgnoreCase(source)) {
            throw new IllegalArgumentException("source must be 'winget' or 'msstore'.");
        }
        return source.toLowerCase(Locale.ROOT);
    }

    private static String optionalScope(Map<String, Object> arguments) {
        String scope = value(arguments, "scope");
        if (scope == null || scope.isBlank()) {
            return null;
        }
        scope = scope.trim();
        if (!"user".equalsIgnoreCase(scope) && !"machine".equalsIgnoreCase(scope)) {
            throw new IllegalArgumentException("scope must be 'user' or 'machine'.");
        }
        return scope.toLowerCase(Locale.ROOT);
    }

    private static String optionalVersion(Map<String, Object> arguments) {
        String version = value(arguments, "version");
        if (version == null || version.isBlank()) {
            return null;
        }
        return WindowsToolArgumentPolicy.requireWingetVersion(version);
    }

    private static Map<String, Object> baseResult(String operation, PackageRequest request, CommandResult commandResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("manager", request.manager());
        result.put("packageId", request.packageId());
        result.put("timeoutSeconds", request.timeoutSeconds());
        result.put("durationMs", commandResult.durationMs());
        result.put("timedOut", commandResult.timedOut());
        result.put("exitCode", commandResult.timedOut() ? null : commandResult.exitCode());
        result.put("exitCodeHex", commandResult.timedOut() ? null : hexExitCode(commandResult.exitCode()));
        result.put("stdout", commandResult.stdout().text());
        result.put("stderr", commandResult.stderr().text());
        result.put("stdoutTruncated", commandResult.stdout().truncated());
        result.put("stderrTruncated", commandResult.stderr().truncated());
        result.put("outputTruncated", commandResult.stdout().truncated() || commandResult.stderr().truncated());
        result.put("limitations", "Does not bypass Windows ACLs, protected services, running file locks, vendor uninstallers, or reboot requirements.");
        return result;
    }

    private static boolean containsExactPackageIdToken(String output, String packageId) {
        if (output == null || output.isBlank() || packageId == null || packageId.isBlank()) {
            return false;
        }
        String expected = packageId.trim();
        for (String line : output.split("\\R")) {
            for (String token : line.trim().split("\\s+")) {
                if (expected.equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String formatExitCode(int exitCode) {
        return exitCode + " (" + hexExitCode(exitCode) + ")";
    }

    private static String hexExitCode(int exitCode) {
        return String.format(Locale.ROOT, "0x%08X", exitCode);
    }

    private static String replacementPackageId(String packageId) {
        return "Tencent.QQ".equalsIgnoreCase(packageId) ? "Tencent.QQ.NT" : null;
    }

    private static int boundedTimeout(String raw, int defaultTimeoutSeconds, int maxTimeoutSeconds) {
        if (raw == null || raw.isBlank()) {
            return defaultTimeoutSeconds;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return Math.max(1, Math.min(parsed, maxTimeoutSeconds));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("timeoutSeconds must be an integer.");
        }
    }

    private static boolean booleanValue(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = value.toString().trim();
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be a boolean.");
    }

    private static String value(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
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

    private record PackageRequest(String packageId, String manager, int timeoutSeconds) {
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, int timeoutSeconds)
                throws IOException, InterruptedException, ExecutionException {
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
