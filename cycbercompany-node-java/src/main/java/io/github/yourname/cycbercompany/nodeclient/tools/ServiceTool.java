package io.github.yourname.cycbercompany.nodeclient.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
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
 * Structured Windows service operations.
 *
 * <p>Service names are passed through environment variables to fixed PowerShell scripts. This keeps
 * the capability focused on service management instead of exposing arbitrary shell execution.
 */
public final class ServiceTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final String SERVICE_NAME_ENV = "CYCBERCOMPANY_SERVICE_NAME";
    private static final String SERVICE_START_MODE_ENV = "CYCBERCOMPANY_SERVICE_START_MODE";
    private static final String QUERY_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $name = $env:CYCBERCOMPANY_SERVICE_NAME
            $svc = Get-CimInstance -ClassName Win32_Service | Where-Object { $_.Name -eq $name } | Select-Object -First 1
            if ($null -eq $svc) { Write-Error "Service not found."; exit 2 }
            $runtime = Get-Service -Name $name -ErrorAction Stop
            [pscustomobject]@{
              name = $svc.Name
              displayName = $svc.DisplayName
              state = $svc.State
              status = $svc.Status
              startMode = $svc.StartMode
              serviceType = $svc.ServiceType
              processId = $svc.ProcessId
              canStop = $runtime.CanStop
            } | ConvertTo-Json -Compress
            """;
    private static final String STOP_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $name = $env:CYCBERCOMPANY_SERVICE_NAME
            Stop-Service -Name $name -Force -ErrorAction Stop
            Start-Sleep -Milliseconds 200
            """ + QUERY_SCRIPT;
    private static final String SET_START_MODE_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $name = $env:CYCBERCOMPANY_SERVICE_NAME
            $mode = $env:CYCBERCOMPANY_SERVICE_START_MODE
            Set-Service -Name $name -StartupType $mode -ErrorAction Stop
            """ + QUERY_SCRIPT;

    private final CommandRunner runner;
    private final boolean windows;

    public ServiceTool() {
        this(new ProcessCommandRunner(), isWindows());
    }

    ServiceTool(CommandRunner runner, boolean windows) {
        this.runner = runner;
        this.windows = windows;
    }

    public ToolExecutionResult query(Map<String, Object> arguments) {
        try {
            ServiceRequest request = request(arguments);
            CommandResult commandResult = runner.run(
                    powershellCommand(QUERY_SCRIPT),
                    Map.of(SERVICE_NAME_ENV, request.serviceName()),
                    request.timeoutSeconds());
            return serviceResult("query", request, commandResult, "Service query failed.");
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start PowerShell for service query.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Service query was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read service query output.");
        }
    }

    public ToolExecutionResult stop(Map<String, Object> arguments) {
        try {
            ServiceRequest request = request(arguments);
            CommandResult commandResult = runner.run(
                    powershellCommand(STOP_SCRIPT),
                    Map.of(SERVICE_NAME_ENV, request.serviceName()),
                    request.timeoutSeconds());
            return serviceResult("stop", request, commandResult,
                    "Service stop failed. The service may not exist, may require an elevated/admin node, or may be protected/not stoppable.");
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start PowerShell for service stop.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Service stop was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read service stop output.");
        }
    }

    public ToolExecutionResult setStartMode(Map<String, Object> arguments) {
        try {
            ServiceRequest request = request(arguments);
            String startMode = startMode(arguments);
            CommandResult commandResult = runner.run(
                    powershellCommand(SET_START_MODE_SCRIPT),
                    Map.of(SERVICE_NAME_ENV, request.serviceName(), SERVICE_START_MODE_ENV, startMode),
                    request.timeoutSeconds());
            Map<String, Object> result = serviceResultMap("set_start_mode", request, commandResult);
            result.put("requestedStartMode", startMode);
            if (commandResult.timedOut()) {
                return ToolExecutionResult.failure(result,
                        "Service start-mode change timed out after " + request.timeoutSeconds() + " seconds.");
            }
            if (commandResult.exitCode() != 0) {
                return ToolExecutionResult.failure(result,
                        "Service start-mode change failed. The service may not exist, may require an elevated/admin node, or may be protected by Windows or security software.");
            }
            return withParsedService(result, commandResult.stdout().text());
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start PowerShell for service start-mode change.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Service start-mode change was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read service start-mode output.");
        }
    }

    private ToolExecutionResult serviceResult(
            String operation,
            ServiceRequest request,
            CommandResult commandResult,
            String failureMessage) {
        Map<String, Object> result = serviceResultMap(operation, request, commandResult);
        if (commandResult.timedOut()) {
            return ToolExecutionResult.failure(result,
                    "Service operation timed out after " + request.timeoutSeconds() + " seconds.");
        }
        if (commandResult.exitCode() != 0) {
            return ToolExecutionResult.failure(result, failureMessage);
        }
        return withParsedService(result, commandResult.stdout().text());
    }

    private static Map<String, Object> serviceResultMap(
            String operation,
            ServiceRequest request,
            CommandResult commandResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("serviceName", request.serviceName());
        result.put("timeoutSeconds", request.timeoutSeconds());
        result.put("durationMs", commandResult.durationMs());
        result.put("timedOut", commandResult.timedOut());
        result.put("exitCode", commandResult.timedOut() ? null : commandResult.exitCode());
        result.put("stdout", commandResult.stdout().text());
        result.put("stderr", commandResult.stderr().text());
        result.put("stdoutTruncated", commandResult.stdout().truncated());
        result.put("stderrTruncated", commandResult.stderr().truncated());
        result.put("outputTruncated", commandResult.stdout().truncated() || commandResult.stderr().truncated());
        result.put("limitations", "Does not bypass Windows ACLs, protected services, NOT_STOPPABLE semantics, security software, or reboot requirements.");
        return result;
    }

    private static ToolExecutionResult withParsedService(Map<String, Object> result, String stdout) {
        try {
            Map<String, Object> service = OBJECT_MAPPER.readValue(stdout, new TypeReference<>() {
            });
            result.put("service", service);
            return ToolExecutionResult.success(result);
        } catch (IOException ex) {
            return ToolExecutionResult.failure(result, "Service operation succeeded but returned an unreadable service snapshot.");
        }
    }

    private ServiceRequest request(Map<String, Object> arguments) {
        if (!windows) {
            throw new IllegalArgumentException("system.service currently supports Windows services only.");
        }
        String serviceName = WindowsToolArgumentPolicy.requireWindowsServiceName(value(arguments, "serviceName"));
        return new ServiceRequest(serviceName, boundedTimeout(value(arguments, "timeoutSeconds")));
    }

    private static String startMode(Map<String, Object> arguments) {
        String startMode = value(arguments, "startMode");
        if (startMode == null || startMode.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: startMode");
        }
        startMode = startMode.trim().toLowerCase(Locale.ROOT);
        return switch (startMode) {
            case "automatic" -> "Automatic";
            case "manual" -> "Manual";
            case "disabled" -> "Disabled";
            default -> throw new IllegalArgumentException("startMode must be automatic, manual, or disabled.");
        };
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

    private record ServiceRequest(String serviceName, int timeoutSeconds) {
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
