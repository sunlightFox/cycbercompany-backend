package io.github.yourname.cycbercompany.nodeclient.tools;

import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
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
    private static final String SERVICE_CONTROL_SOCKET = "/run/cycbercompany/service-control.sock";
    private final SoftwareTool.CommandRunner runner;
    private final RestartClient restartClient;

    public LinuxServiceTool() {
        this(new SoftwareTool.ProcessCommandRunner(), LinuxServiceTool::restartThroughControlSocket);
    }

    LinuxServiceTool(SoftwareTool.CommandRunner runner) {
        this(runner, LinuxServiceTool::restartThroughControlSocket);
    }

    LinuxServiceTool(SoftwareTool.CommandRunner runner, RestartClient restartClient) {
        this.runner = runner;
        this.restartClient = restartClient;
    }

    public ToolExecutionResult query(Map<String, Object> arguments) {
        return execute("query", arguments, request -> List.of("systemctl", "show", request.serviceName(), "--no-page",
                "--property=Id,Description,LoadState,ActiveState,SubState,UnitFileState,MainPID,CanStop"));
    }

    public ToolExecutionResult stop(Map<String, Object> arguments) {
        return execute("stop", arguments, request -> List.of("sudo", "-n", "systemctl", "stop", request.serviceName()));
    }

    /** Restarts an allowlisted unit through the root-owned service-control helper. */
    public ToolExecutionResult restart(Map<String, Object> arguments) {
        try {
            Request request = request(arguments);
            return complete("restart", request, restartClient.restart(request));
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("The restricted Linux service-control socket is unavailable.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Service restart was interrupted.");
        }
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
    @FunctionalInterface
    interface RestartClient { SoftwareTool.CommandResult restart(Request request) throws IOException, InterruptedException; }
    record Request(String serviceName, int timeoutSeconds) {}

    private static SoftwareTool.CommandResult restartThroughControlSocket(Request request) throws IOException, InterruptedException {
        long started = System.nanoTime();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            channel.connect(UnixDomainSocketAddress.of(SERVICE_CONTROL_SOCKET));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
            while (!channel.finishConnect()) {
                awaitSocket(deadline);
            }
            ByteBuffer requestBuffer = StandardCharsets.UTF_8.encode("restart " + request.serviceName() + "\n");
            while (requestBuffer.hasRemaining()) {
                channel.write(requestBuffer);
                awaitSocket(deadline);
            }
            ByteBuffer responseBuffer = ByteBuffer.allocate(512);
            while (channel.read(responseBuffer) == 0) {
                awaitSocket(deadline);
            }
            String response = StandardCharsets.UTF_8.decode((ByteBuffer) responseBuffer.flip()).toString().trim();
            boolean success = response.startsWith("OK ");
            return new SoftwareTool.CommandResult(success ? 0 : 1,
                    new SoftwareTool.CapturedOutput(success ? response : "", false),
                    new SoftwareTool.CapturedOutput(success ? "" : response, false), false,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private static void awaitSocket(long deadline) throws IOException, InterruptedException {
        if (System.nanoTime() >= deadline) throw new IOException("Timed out waiting for service-control socket.");
        Thread.sleep(10);
    }
}
