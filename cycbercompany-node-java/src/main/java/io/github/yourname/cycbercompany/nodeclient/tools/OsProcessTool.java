package io.github.yourname.cycbercompany.nodeclient.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
 * Structured Windows OS process inspection and termination.
 *
 * <p>This is separate from {@code process.*}, which manages only processes started by this node.
 * Existing OS processes are addressed by exact image name and optional process IDs.
 */
public final class OsProcessTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_PROCESS_IDS = 32;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 20;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final String PROCESS_NAME_ENV = "CYCBERCOMPANY_PROCESS_NAME";
    private static final String PROCESS_IDS_ENV = "CYCBERCOMPANY_PROCESS_IDS";
    private static final String PROCESS_ALL_ENV = "CYCBERCOMPANY_PROCESS_ALL";
    private static final String PROCESS_SNAPSHOT_SCRIPT = """
            $name = $env:CYCBERCOMPANY_PROCESS_NAME
            $items = @(Get-CimInstance -ClassName Win32_Process | Where-Object { $_.Name -eq $name } | Select-Object -First 100)
            [pscustomobject]@{
              processName = $name
              count = $items.Count
              truncated = $items.Count -ge 100
              processes = @($items | ForEach-Object {
                [pscustomobject]@{
                  processId = $_.ProcessId
                  name = $_.Name
                  parentProcessId = $_.ParentProcessId
                  sessionId = $_.SessionId
                }
              })
            } | ConvertTo-Json -Depth 4 -Compress
            """;
    private static final String QUERY_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            """ + PROCESS_SNAPSHOT_SCRIPT;
    private static final String TERMINATE_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $name = $env:CYCBERCOMPANY_PROCESS_NAME
            $all = $env:CYCBERCOMPANY_PROCESS_ALL -eq 'true'
            $rawIds = $env:CYCBERCOMPANY_PROCESS_IDS
            $requestedIds = @()
            if ($rawIds -and $rawIds.Trim().Length -gt 0) {
              $requestedIds = @($rawIds.Split(',') | Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { [int]$_.Trim() })
            }
            if (-not $all -and $requestedIds.Count -eq 0) {
              Write-Error "No process IDs were supplied and allMatching was not true."
              exit 3
            }
            $matches = @(Get-CimInstance -ClassName Win32_Process | Where-Object { $_.Name -eq $name })
            if ($requestedIds.Count -gt 0) {
              $targets = @($matches | Where-Object { $requestedIds -contains [int]$_.ProcessId })
            } else {
              $targets = $matches
            }
            foreach ($target in $targets) {
              Stop-Process -Id $target.ProcessId -Force -ErrorAction Stop
            }
            Start-Sleep -Milliseconds 200
            $remaining = @(Get-CimInstance -ClassName Win32_Process | Where-Object { $_.Name -eq $name } | Select-Object -First 100)
            [pscustomobject]@{
              processName = $name
              allMatching = $all
              requestedProcessIds = @($requestedIds)
              targetedProcessIds = @($targets | ForEach-Object { $_.ProcessId })
              terminatedCount = $targets.Count
              remainingCount = $remaining.Count
              remaining = @($remaining | ForEach-Object {
                [pscustomobject]@{
                  processId = $_.ProcessId
                  name = $_.Name
                  parentProcessId = $_.ParentProcessId
                  sessionId = $_.SessionId
                }
              })
            } | ConvertTo-Json -Depth 4 -Compress
            """;

    private final CommandRunner runner;
    private final boolean windows;

    public OsProcessTool() {
        this(new ProcessCommandRunner(), isWindows());
    }

    OsProcessTool(CommandRunner runner, boolean windows) {
        this.runner = runner;
        this.windows = windows;
    }

    public ToolExecutionResult query(Map<String, Object> arguments) {
        try {
            ProcessRequest request = request(arguments);
            CommandResult commandResult = runner.run(
                    powershellCommand(QUERY_SCRIPT),
                    Map.of(PROCESS_NAME_ENV, request.processName()),
                    request.timeoutSeconds());
            return processResult("query", request, commandResult, "OS process query failed.");
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start PowerShell for OS process query.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("OS process query was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read OS process query output.");
        }
    }

    public ToolExecutionResult terminate(Map<String, Object> arguments) {
        try {
            ProcessRequest request = request(arguments);
            List<Integer> processIds = processIds(arguments);
            boolean allMatching = booleanValue(arguments, "allMatching", false);
            if (processIds.isEmpty() && !allMatching) {
                return ToolExecutionResult.failure("Supply processIds from system.os_process.query or set allMatching=true explicitly.");
            }
            CommandResult commandResult = runner.run(
                    powershellCommand(TERMINATE_SCRIPT),
                    Map.of(
                            PROCESS_NAME_ENV, request.processName(),
                            PROCESS_IDS_ENV, commaSeparated(processIds),
                            PROCESS_ALL_ENV, Boolean.toString(allMatching)),
                    request.timeoutSeconds());
            return processResult("terminate", request, commandResult,
                    "OS process termination failed. The process may not exist, may require an elevated/admin node, or may be protected.");
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start PowerShell for OS process termination.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("OS process termination was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read OS process termination output.");
        }
    }

    private ToolExecutionResult processResult(
            String operation,
            ProcessRequest request,
            CommandResult commandResult,
            String failureMessage) {
        Map<String, Object> result = processResultMap(operation, request, commandResult);
        if (commandResult.timedOut()) {
            return ToolExecutionResult.failure(result,
                    "OS process operation timed out after " + request.timeoutSeconds() + " seconds.");
        }
        if (commandResult.exitCode() != 0) {
            return ToolExecutionResult.failure(result, failureMessage);
        }
        try {
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(commandResult.stdout().text(), new TypeReference<>() {
            });
            result.put("snapshot", snapshot);
            return ToolExecutionResult.success(result);
        } catch (IOException ex) {
            return ToolExecutionResult.failure(result, "OS process operation succeeded but returned an unreadable snapshot.");
        }
    }

    private static Map<String, Object> processResultMap(
            String operation,
            ProcessRequest request,
            CommandResult commandResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("processName", request.processName());
        result.put("timeoutSeconds", request.timeoutSeconds());
        result.put("durationMs", commandResult.durationMs());
        result.put("timedOut", commandResult.timedOut());
        result.put("exitCode", commandResult.timedOut() ? null : commandResult.exitCode());
        result.put("stdout", commandResult.stdout().text());
        result.put("stderr", commandResult.stderr().text());
        result.put("stdoutTruncated", commandResult.stdout().truncated());
        result.put("stderrTruncated", commandResult.stderr().truncated());
        result.put("outputTruncated", commandResult.stdout().truncated() || commandResult.stderr().truncated());
        result.put("limitations", "Does not bypass Windows ACLs, protected processes, security software, or service-control restrictions.");
        return result;
    }

    private ProcessRequest request(Map<String, Object> arguments) {
        if (!windows) {
            throw new IllegalArgumentException("system.os_process currently supports Windows processes only.");
        }
        String processName = WindowsToolArgumentPolicy.requireWindowsProcessName(value(arguments, "processName"));
        return new ProcessRequest(processName, boundedTimeout(value(arguments, "timeoutSeconds")));
    }

    private static List<Integer> processIds(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("processIds");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("processIds must be an array of positive integers.");
        }
        if (values.size() > MAX_PROCESS_IDS) {
            throw new IllegalArgumentException("processIds cannot contain more than " + MAX_PROCESS_IDS + " entries.");
        }
        List<Integer> result = new ArrayList<>();
        for (Object value : values) {
            int processId;
            try {
                processId = Integer.parseInt(value.toString());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("processIds must contain only positive integers.");
            }
            if (processId <= 0) {
                throw new IllegalArgumentException("processIds must contain only positive integers.");
            }
            result.add(processId);
        }
        return List.copyOf(result);
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

    private static int boundedTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return Math.max(1, Math.min(parsed, MAX_TIMEOUT_SECONDS));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("timeoutSeconds must be an integer.");
        }
    }

    private static String commaSeparated(List<Integer> values) {
        return String.join(",", values.stream().map(Object::toString).toList());
    }

    private static List<String> powershellCommand(String script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
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
        CommandResult run(List<String> command, Map<String, String> environment, int timeoutSeconds)
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

    private record ProcessRequest(String processName, int timeoutSeconds) {
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, Map<String, String> environment, int timeoutSeconds)
                throws IOException, InterruptedException, ExecutionException {
            long startedAt = System.nanoTime();
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().putAll(environment);
            Process process = processBuilder.start();
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
