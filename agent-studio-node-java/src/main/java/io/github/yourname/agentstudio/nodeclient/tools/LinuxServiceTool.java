package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/** Structured systemd service operations for Linux nodes without a shell escape hatch. */
public final class LinuxServiceTool {

    private static final Pattern UNIT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.@-]{0,251}\\.service");
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private final SoftwareTool.CommandRunner runner;

    public LinuxServiceTool() {
        this(new SoftwareTool.ProcessCommandRunner());
    }

    LinuxServiceTool(SoftwareTool.CommandRunner runner) {
        this.runner = runner;
    }

    public ToolExecutionResult query(Map<String, Object> arguments) {
        return execute("query", arguments, request -> List.of("systemctl", "show", request.serviceName(), "--no-page",
                "--property=Id,Description,LoadState,ActiveState,SubState,UnitFileState,MainPID,CanStop"));
    }

    public ToolExecutionResult stop(Map<String, Object> arguments) {
        return execute("stop", arguments, request -> List.of("sudo", "-n", "systemctl", "stop", request.serviceName()));
    }

    public ToolExecutionResult setStartMode(Map<String, Object> arguments) {
        try {
            Request request = request(arguments);
            String mode = value(arguments, "startMode");
            if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Missing required argument: startMode");
            List<String> command = switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "automatic" -> List.of("sudo", "-n", "systemctl", "enable", request.serviceName());
                case "manual" -> List.of("sudo", "-n", "systemctl", "disable", request.serviceName());
                case "disabled" -> List.of("sudo", "-n", "systemctl", "mask", request.serviceName());
                default -> throw new IllegalArgumentException("startMode must be automatic, manual, or disabled.");
            };
            ToolExecutionResult result = complete("set_start_mode", request, runner.run(command, request.timeoutSeconds()));
            if (result.success()) {
                Map<String, Object> data = new LinkedHashMap<>(result.result());
                data.put("requestedStartMode", mode.trim().toLowerCase(java.util.Locale.ROOT));
                return ToolExecutionResult.success(data);
            }
            return result;
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start systemctl. This Linux node requires systemd.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Service start-mode change was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read systemctl output.");
        }
    }

    private ToolExecutionResult execute(String operation, Map<String, Object> arguments, Command command) {
        try {
            Request request = request(arguments);
            return complete(operation, request, runner.run(command.build(request), request.timeoutSeconds()));
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start systemctl. This Linux node requires systemd.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Service " + operation + " was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read systemctl output.");
        }
    }

    private static ToolExecutionResult complete(String operation, Request request, SoftwareTool.CommandResult command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("manager", "systemd");
        result.put("serviceName", request.serviceName());
        result.put("timeoutSeconds", request.timeoutSeconds());
        result.put("durationMs", command.durationMs());
        result.put("timedOut", command.timedOut());
        result.put("exitCode", command.timedOut() ? null : command.exitCode());
        result.put("stdout", command.stdout().text());
        result.put("stderr", command.stderr().text());
        result.put("limitations", "Supports exact systemd .service units only; does not accept arbitrary systemctl options or shell commands.");
        if (command.timedOut()) return ToolExecutionResult.failure(result, "Service operation timed out after " + request.timeoutSeconds() + " seconds.");
        if (command.exitCode() != 0) return ToolExecutionResult.failure(result,
                "systemctl " + operation + " failed. The service may not exist, may require passwordless sudo, or the node may not run systemd.");
        return ToolExecutionResult.success(result);
    }

    private static Request request(Map<String, Object> arguments) {
        String name = value(arguments, "serviceName");
        if (name == null || !UNIT_NAME.matcher(name.trim()).matches()) {
            throw new IllegalArgumentException("serviceName must be one exact systemd .service unit without paths, spaces, flags, or shell syntax.");
        }
        String rawTimeout = value(arguments, "timeoutSeconds");
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (rawTimeout != null && !rawTimeout.isBlank()) {
            try { timeout = Math.max(1, Math.min(Integer.parseInt(rawTimeout), MAX_TIMEOUT_SECONDS)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("timeoutSeconds must be an integer."); }
        }
        return new Request(name.trim(), timeout);
    }

    private static String value(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    @FunctionalInterface
    private interface Command { List<String> build(Request request); }
    private record Request(String serviceName, int timeoutSeconds) {}
}
