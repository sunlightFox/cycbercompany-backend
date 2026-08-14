package io.github.yourname.cycbercompany.nodeclient.tools;

import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/** Structured Debian/Ubuntu package operations that never invoke a shell. */
public final class LinuxSoftwareTool {

    private static final Pattern PACKAGE_NAME = Pattern.compile("[a-z0-9][a-z0-9+.-]{0,127}");
    private static final int QUERY_TIMEOUT = 60;
    private static final int LIST_TIMEOUT = 90;
    private static final int INSTALL_TIMEOUT = 600;
    private static final int UNINSTALL_TIMEOUT = 300;
    private static final int MAX_TIMEOUT = 600;
    private static final int MAX_INSTALL_TIMEOUT = 1_200;
    private static final String SERVICE_CONTROL_SOCKET = "/run/cycbercompany/service-control.sock";
    private final SoftwareTool.CommandRunner runner;
    private final InstallClient installClient;

    public LinuxSoftwareTool() {
        this(new SoftwareTool.ProcessCommandRunner(), LinuxSoftwareTool::installThroughControlSocket);
    }

    LinuxSoftwareTool(SoftwareTool.CommandRunner runner) {
        this(runner, LinuxSoftwareTool::installThroughControlSocket);
    }

    LinuxSoftwareTool(SoftwareTool.CommandRunner runner, InstallClient installClient) {
        this.runner = runner;
        this.installClient = installClient;
    }

    public ToolExecutionResult query(Map<String, Object> arguments) {
        try {
            Request request = request(arguments, QUERY_TIMEOUT, MAX_TIMEOUT);
            SoftwareTool.CommandResult command = runner.run(List.of(
                    "dpkg-query", "-W", "-f=${Status}\\t${Version}\\n", request.packageName()), request.timeoutSeconds());
            Map<String, Object> result = result("query", request, command);
            result.put("installed", command.exitCode() == 0 && command.stdout().text().contains("install ok installed"));
            if (command.timedOut()) return ToolExecutionResult.failure(result, "Software query timed out after " + request.timeoutSeconds() + " seconds.");
            if (command.exitCode() == 1) {
                result.put("installed", false);
                result.put("queryStatus", "NOT_INSTALLED");
                return ToolExecutionResult.success(result);
            }
            return command.exitCode() == 0 ? ToolExecutionResult.success(result)
                    : ToolExecutionResult.failure(result, "dpkg-query exited with code " + command.exitCode() + ".");
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start dpkg-query. This Linux node requires Debian or Ubuntu package tools.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Software query was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read dpkg-query output.");
        }
    }

    public ToolExecutionResult list(Map<String, Object> arguments) {
        try {
            int timeout = timeout(arguments, LIST_TIMEOUT, MAX_TIMEOUT);
            SoftwareTool.CommandResult command = runner.run(List.of(
                    "dpkg-query", "-W", "-f=${binary:Package}\\t${Version}\\t${db:Status-Status}\\n"), timeout);
            Map<String, Object> result = result("list", null, command);
            result.put("timeoutSeconds", timeout);
            if (command.timedOut()) return ToolExecutionResult.failure(result, "Software inventory timed out after " + timeout + " seconds.");
            return command.exitCode() == 0 ? ToolExecutionResult.success(result)
                    : ToolExecutionResult.failure(result, "dpkg-query inventory exited with code " + command.exitCode() + ".");
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start dpkg-query. This Linux node requires Debian or Ubuntu package tools.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Software inventory was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read dpkg-query output.");
        }
    }

    public ToolExecutionResult install(Map<String, Object> arguments) {
        try {
            Request request = request(arguments, INSTALL_TIMEOUT, MAX_INSTALL_TIMEOUT);
            boolean allowUpgrade = bool(arguments, "allowUpgrade", false);
            SoftwareTool.CommandResult completed = installClient.install(request, allowUpgrade);
            Map<String, Object> result = result("install", request, completed);
            result.put("allowUpgrade", allowUpgrade);
            return mutation("install", request, completed, result);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("The restricted Linux package-install service is unavailable.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Software install was interrupted.");
        }
    }

    public ToolExecutionResult uninstall(Map<String, Object> arguments) {
        try {
            Request request = request(arguments, UNINSTALL_TIMEOUT, MAX_TIMEOUT);
            SoftwareTool.CommandResult command = runner.run(List.of("sudo", "-n", "env", "DEBIAN_FRONTEND=noninteractive",
                    "apt-get", "remove", "-y", request.packageName()), request.timeoutSeconds());
            return mutation("uninstall", request, command, result("uninstall", request, command));
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        } catch (IOException ex) {
            return ToolExecutionResult.failure("Failed to start apt-get. This Linux node requires Debian or Ubuntu package tools.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("Software uninstall was interrupted.");
        } catch (ExecutionException ex) {
            return ToolExecutionResult.failure("Failed to read apt-get output.");
        }
    }

    private static ToolExecutionResult mutation(String operation, Request request, SoftwareTool.CommandResult command,
            Map<String, Object> result) {
        if (command.timedOut()) return ToolExecutionResult.failure(result,
                "Software " + operation + " timed out after " + request.timeoutSeconds() + " seconds.");
        if (command.exitCode() != 0) return ToolExecutionResult.failure(result,
                "apt-get " + operation + " exited with code " + command.exitCode()
                        + ". Repositories must be reachable, the package must exist, and the restricted package service must be available.");
        return ToolExecutionResult.success(result);
    }

    private static Request request(Map<String, Object> arguments, int defaultTimeout, int maxTimeout) {
        String manager = value(arguments, "manager");
        if (manager != null && !manager.isBlank() && !"apt".equalsIgnoreCase(manager.trim())) {
            throw new IllegalArgumentException("manager must be 'apt'.");
        }
        String packageName = value(arguments, "packageId");
        String normalized = packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
        if (!PACKAGE_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("packageId must be one lowercase Debian package name without paths, spaces, flags, or shell syntax.");
        }
        return new Request(normalized, timeout(arguments, defaultTimeout, maxTimeout));
    }

    private static int timeout(Map<String, Object> arguments, int defaultValue, int maxValue) {
        String raw = value(arguments, "timeoutSeconds");
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Math.max(1, Math.min(Integer.parseInt(raw), maxValue)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("timeoutSeconds must be an integer."); }
    }

    private static boolean bool(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(value.toString().trim())) return true;
        if ("false".equalsIgnoreCase(value.toString().trim())) return false;
        throw new IllegalArgumentException(key + " must be a boolean.");
    }

    private static Map<String, Object> result(String operation, Request request, SoftwareTool.CommandResult command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("manager", "apt");
        if (request != null) {
            result.put("packageId", request.packageName());
            result.put("timeoutSeconds", request.timeoutSeconds());
        }
        result.put("durationMs", command.durationMs());
        result.put("timedOut", command.timedOut());
        result.put("exitCode", command.timedOut() ? null : command.exitCode());
        result.put("stdout", command.stdout().text());
        result.put("stderr", command.stderr().text());
        result.put("stdoutTruncated", command.stdout().truncated());
        result.put("stderrTruncated", command.stderr().truncated());
        result.put("outputTruncated", command.stdout().truncated() || command.stderr().truncated());
        result.put("limitations", "Supports Debian and Ubuntu packages only; does not accept arbitrary apt options or shell commands.");
        return result;
    }

    private static String value(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    record Request(String packageName, int timeoutSeconds) {}

    @FunctionalInterface
    interface InstallClient {
        SoftwareTool.CommandResult install(Request request, boolean allowUpgrade) throws IOException, InterruptedException;
    }

    private static SoftwareTool.CommandResult installThroughControlSocket(Request request, boolean allowUpgrade)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            channel.connect(UnixDomainSocketAddress.of(SERVICE_CONTROL_SOCKET));
            while (!channel.finishConnect()) awaitSocket(deadline);
            ByteBuffer requestBuffer = StandardCharsets.US_ASCII.encode(
                    "install " + request.packageName() + " " + (allowUpgrade ? "1" : "0") + "\n");
            while (requestBuffer.hasRemaining()) {
                channel.write(requestBuffer);
                awaitSocket(deadline);
            }
            ByteBuffer responseBuffer = ByteBuffer.allocate(70 * 1024);
            while (true) {
                int read = channel.read(responseBuffer);
                if (read < 0) break;
                if (read == 0) awaitSocket(deadline);
                if (!responseBuffer.hasRemaining()) throw new IOException("Package service response exceeded its limit.");
            }
            return parseInstallResponse(StandardCharsets.UTF_8.decode((ByteBuffer) responseBuffer.flip()).toString(), started);
        } catch (IOException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Timed out waiting")) {
                return new SoftwareTool.CommandResult(124, new SoftwareTool.CapturedOutput("", false),
                        new SoftwareTool.CapturedOutput(ex.getMessage(), false), true,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            throw ex;
        }
    }

    private static SoftwareTool.CommandResult parseInstallResponse(String response, long started) throws IOException {
        String[] lines = response.split("\\n", -1);
        if (lines.length < 3 || !lines[0].startsWith("RESULT ") || !lines[1].startsWith("OUT ") || !lines[2].startsWith("ERR ")) {
            throw new IOException("Invalid response from restricted package service.");
        }
        String[] header = lines[0].split(" ");
        if (header.length != 5) throw new IOException("Invalid package service result header.");
        try {
            int exitCode = Integer.parseInt(header[1]);
            boolean timedOut = flag(header[2]);
            boolean stdoutTruncated = flag(header[3]);
            boolean stderrTruncated = flag(header[4]);
            String stdout = new String(Base64.getDecoder().decode(lines[1].substring(4)), StandardCharsets.UTF_8);
            String stderr = new String(Base64.getDecoder().decode(lines[2].substring(4)), StandardCharsets.UTF_8);
            return new SoftwareTool.CommandResult(exitCode, new SoftwareTool.CapturedOutput(stdout, stdoutTruncated),
                    new SoftwareTool.CapturedOutput(stderr, stderrTruncated), timedOut,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid package service response.", ex);
        }
    }

    private static boolean flag(String value) throws IOException {
        if ("0".equals(value)) return false;
        if ("1".equals(value)) return true;
        throw new IOException("Invalid package service result flag.");
    }

    private static void awaitSocket(long deadline) throws IOException, InterruptedException {
        if (System.nanoTime() >= deadline) throw new IOException("Timed out waiting for restricted package service.");
        Thread.sleep(10);
    }
}
