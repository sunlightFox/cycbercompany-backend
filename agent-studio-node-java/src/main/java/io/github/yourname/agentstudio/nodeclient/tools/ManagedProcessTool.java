package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Starts development processes under an explicit node-owned handle.
 *
 * <p>Unlike a detached shell command, the returned handle remains associated with the root process
 * and its descendants, so a later {@code process.stop} can reliably clean up a local server.
 */
public final class ManagedProcessTool implements AutoCloseable {

    private static final int MAX_COMMAND_CHARS = 8_000;
    private static final int MAX_PROCESSES = 32;
    private static final int DEFAULT_LOG_CHARS = 12_000;
    private static final int MAX_LOG_CHARS = 32_000;
    /** HTTP 就绪探测不应无限占用节点工具循环。 */
    private static final int DEFAULT_HTTP_WAIT_MILLIS = 30_000;
    private static final int MIN_HTTP_WAIT_MILLIS = 100;
    private static final int MAX_HTTP_WAIT_MILLIS = 120_000;
    private static final int HTTP_POLL_MILLIS = 250;
    private static final int MAX_HTTP_REQUEST_MILLIS = 2_000;

    private final Path workspaceRoot;
    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    /**
     * 此客户端没有 Cookie、认证头或自动重定向配置。探测只使用 GET，并丢弃响应体，
     * 因此不会把应用返回的业务数据带回模型上下文。
     */
    private final HttpClient readinessHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ManagedProcessTool(Path workspaceRoot) {
        try {
            if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
                throw new IllegalArgumentException("Workspace must be an existing directory: " + workspaceRoot);
            }
            this.workspaceRoot = workspaceRoot.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve workspace: " + workspaceRoot, ex);
        }
    }

    public ToolExecutionResult start(Map<String, Object> arguments) {
        String command = value(arguments, "command");
        if (command == null || command.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: command");
        }
        if (command.length() > MAX_COMMAND_CHARS) {
            return ToolExecutionResult.failure("Command exceeds the " + MAX_COMMAND_CHARS + " character limit.");
        }
        if (startsDetachedProcess(command)) {
            return ToolExecutionResult.failure(
                    "process.start manages the command itself; do not use Start-Process, nohup, or a trailing '&'.");
        }
        long activeProcesses = processes.values().stream().filter(process -> process.process().isAlive()).count();
        if (activeProcesses >= MAX_PROCESSES) {
            return ToolExecutionResult.failure("Too many managed processes. Stop an existing process first.");
        }

        try {
            Path cwd = resolveDirectory(value(arguments, "cwd"));
            String processId = "proc_" + UUID.randomUUID();
            Path logs = workspaceRoot.resolve(".agent-studio").resolve("processes");
            Files.createDirectories(logs);
            Path stdout = resolveOutputPath(value(arguments, "stdoutPath"), logs.resolve(processId + ".out.log"));
            Path stderr = resolveOutputPath(value(arguments, "stderrPath"), logs.resolve(processId + ".err.log"));
            Files.createDirectories(stdout.getParent());
            Files.createDirectories(stderr.getParent());

            Process process = new ProcessBuilder(shellCommand(command))
                    .directory(cwd.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            ManagedProcess managed = new ManagedProcess(processId, command, cwd, stdout, stderr, process, Instant.now());
            processes.put(processId, managed);
            return ToolExecutionResult.success(snapshot(managed));
        } catch (Exception ex) {
            // 启动异常可能拼接了本机目录、可执行文件或命令行；调用方只需知道本次未启动，
            // 详细错误由节点本地日志和受权限保护的调用记录保留。
            return ToolExecutionResult.failure("process.start failed.");
        }
    }

    public ToolExecutionResult status(Map<String, Object> arguments) {
        ManagedProcess process = require(arguments);
        return process == null
                ? ToolExecutionResult.failure("Unknown managed process. Pass the processId returned by process.start.")
                : ToolExecutionResult.success(snapshot(process));
    }

    /**
     * 读取指定托管进程日志的尾部，供联调时快速判断服务是否启动、端口是否冲突或测试为何失败。
     *
     * <p>调用方只能选择 process.start 返回的进程句柄和 stdout/stderr 流，不能把此能力变成任意文件读取；
     * 日志按字符上限截取并标记 truncated，避免把持续运行的开发服务器日志无限塞进模型上下文。
     */
    public ToolExecutionResult logs(Map<String, Object> arguments) {
        ManagedProcess managed = require(arguments);
        if (managed == null) {
            return ToolExecutionResult.failure("Unknown managed process. Pass the processId returned by process.start.");
        }
        String stream = value(arguments, "stream");
        if (stream == null || stream.isBlank()) {
            stream = "stdout";
        }
        if (!"stdout".equalsIgnoreCase(stream) && !"stderr".equalsIgnoreCase(stream)) {
            return ToolExecutionResult.failure("stream must be stdout or stderr.");
        }
        int maxChars;
        try {
            maxChars = boundedLogChars(value(arguments, "maxChars"));
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }
        Path logPath = "stderr".equalsIgnoreCase(stream) ? managed.stderr() : managed.stdout();
        try {
            TailLog tailLog = readTail(logPath, maxChars);
            Map<String, Object> result = snapshot(managed);
            result.put("stream", stream.toLowerCase(Locale.ROOT));
            // 日志路径仅供节点本地打开文件。模型已持有 processId 和 stream，返回绝对路径会暴露
            // 节点磁盘布局，也不能帮助下一次 process.logs 调用，因此刻意不向上游返回。
            result.put("content", tailLog.content());
            result.put("truncated", tailLog.truncated());
            result.put("maxChars", maxChars);
            return ToolExecutionResult.success(result);
        } catch (IOException ex) {
            return ToolExecutionResult.failure("process.logs failed to read the managed stream.");
        }
    }

    /**
     * 等待一个节点托管的本机开发服务真正可以接受 HTTP 请求。
     *
     * <p>它刻意不是通用 HTTP 客户端：调用方必须先持有 {@link #start(Map)} 返回的进程句柄，
     * 地址只能是 localhost、127.0.0.1 或 ::1，且 URL 不允许携带账号、查询参数或片段。
     * 这样既能让前后端联调可靠地等待服务就绪，也不会被用来探测远程网络、传递 token，
     * 或读取接口的响应内容。
     */
    public ToolExecutionResult waitHttp(Map<String, Object> arguments) {
        ManagedProcess managed = require(arguments);
        if (managed == null) {
            return ToolExecutionResult.failure("Unknown managed process. Pass the processId returned by process.start.");
        }

        URI endpoint;
        int timeoutMillis;
        Integer expectedStatus;
        try {
            endpoint = localHttpEndpoint(value(arguments, "url"));
            timeoutMillis = boundedHttpWaitMillis(value(arguments, "timeoutMs"));
            expectedStatus = expectedHttpStatus(value(arguments, "expectedStatus"));
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }

        Instant started = Instant.now();
        Instant deadline = started.plusMillis(timeoutMillis);
        int attempts = 0;
        Integer lastStatus = null;
        boolean processStopped = false;
        while (Instant.now().isBefore(deadline) || attempts == 0) {
            if (!managed.process().isAlive()) {
                processStopped = true;
                break;
            }
            attempts++;
            try {
                long remaining = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        // 每次请求的超时必须小于总等待时间，防止单个卡住的连接越过总预算。
                        .timeout(Duration.ofMillis(Math.min(MAX_HTTP_REQUEST_MILLIS, remaining)))
                        .GET()
                        .build();
                HttpResponse<Void> response = readinessHttpClient.send(request, HttpResponse.BodyHandlers.discarding());
                lastStatus = response.statusCode();
                if (isExpectedStatus(lastStatus, expectedStatus)) {
                    return ToolExecutionResult.success(readinessSnapshot(
                            endpoint, managed, attempts, started, lastStatus, expectedStatus, true));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return ToolExecutionResult.failure(readinessSnapshot(
                        endpoint, managed, attempts, started, lastStatus, expectedStatus, false),
                        "process.wait_http was interrupted before the local service became ready.");
            } catch (IOException | RuntimeException ignored) {
                // 启动窗口中的拒绝连接、临时 5xx 或 HTTP 协议错误是正常轮询状态；
                // 不回传异常详情，避免把本机路径、证书或服务内部信息写入调用结果。
            }

            long remaining = Duration.between(Instant.now(), deadline).toMillis();
            if (remaining <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(HTTP_POLL_MILLIS, remaining));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return ToolExecutionResult.failure(readinessSnapshot(
                        endpoint, managed, attempts, started, lastStatus, expectedStatus, false),
                        "process.wait_http was interrupted before the local service became ready.");
            }
        }

        String reason = processStopped
                ? "Managed process stopped before the local HTTP service became ready."
                : "Local HTTP service did not become ready before the timeout.";
        return ToolExecutionResult.failure(
                readinessSnapshot(endpoint, managed, attempts, started, lastStatus, expectedStatus, false), reason);
    }

    public ToolExecutionResult stop(Map<String, Object> arguments) {
        ManagedProcess managed = require(arguments);
        if (managed == null) {
            return ToolExecutionResult.failure("Unknown managed process. Pass the processId returned by process.start.");
        }
        try {
            terminateTree(managed.process(), false);
            if (managed.process().isAlive()) {
                managed.process().waitFor(2, TimeUnit.SECONDS);
            }
            if (managed.process().isAlive()) {
                terminateTree(managed.process(), true);
                managed.process().waitFor(2, TimeUnit.SECONDS);
            }
            Map<String, Object> result = snapshot(managed);
            result.put("stopped", !managed.process().isAlive());
            return managed.process().isAlive()
                    ? ToolExecutionResult.failure(result, "Managed process did not stop within the allowed time.")
                    : ToolExecutionResult.success(result);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("process.stop was interrupted.");
        } catch (Exception ex) {
            return ToolExecutionResult.failure("process.stop failed: " + message(ex));
        }
    }

    @Override
    public void close() {
        for (ManagedProcess process : processes.values()) {
            try {
                terminateTree(process.process(), true);
            } catch (Exception ignored) {
                // Shutdown should continue trying remaining managed processes.
            }
        }
        processes.clear();
    }

    private ManagedProcess require(Map<String, Object> arguments) {
        String processId = value(arguments, "processId");
        return processId == null || processId.isBlank() ? null : processes.get(processId);
    }

    private Map<String, Object> snapshot(ManagedProcess managed) {
        Process process = managed.process();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processId", managed.id());
        result.put("active", process.isAlive());
        // processId 是节点创建的不可猜测句柄，足以作为 status/logs/stop/wait_http 的后续参数。
        // 不返回系统 PID、命令和绝对路径，避免模型上下文、运行审计摘要或最终回答泄露本机环境细节。
        result.put("workingDirectory", workspaceRelative(managed.cwd()));
        result.put("startedAt", managed.startedAt().toString());
        if (!process.isAlive()) {
            result.put("exitCode", process.exitValue());
        }
        return result;
    }

    /**
     * 所有对外目录字段统一为工作区相对路径。
     *
     * <p>ManagedProcess 的目录来自 {@link #resolveDirectory(String)}，已验证在工作区内；这里仍然
     * 再做一次 normalize 和 startsWith 检查，避免未来调用方修改内部对象后意外把绝对路径带出节点。
     */
    private String workspaceRelative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new IllegalStateException("Managed process path escaped the configured workspace.");
        }
        String relative = workspaceRoot.relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private Path resolveDirectory(String requested) throws IOException {
        Path candidate = requested == null || requested.isBlank() ? workspaceRoot : Path.of(requested);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("Working directory does not exist: " + candidate);
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Working directory must stay inside the configured workspace.");
        }
        return real;
    }

    private Path resolveOutputPath(String requested, Path fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        Path candidate = Path.of(requested);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Log path must stay inside the configured workspace.");
        }
        return candidate;
    }

    private static boolean startsDetachedProcess(String command) {
        String normalized = command.toLowerCase(Locale.ROOT).trim();
        return normalized.contains("start-process")
                || normalized.contains("nohup ")
                || normalized.endsWith(" &");
    }

    /** 只接受字面量回环主机名，不做 DNS 解析，避免 localhost 重绑定或内网绕过。 */
    private static URI localHttpEndpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: url");
        }
        try {
            URI endpoint = new URI(raw.trim());
            String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
            String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))) {
                throw new IllegalArgumentException("process.wait_http supports only http:// or https:// URLs.");
            }
            if (!("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                    || "[::1]".equals(host))) {
                throw new IllegalArgumentException("process.wait_http accepts only localhost, 127.0.0.1, or ::1.");
            }
            if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
                throw new IllegalArgumentException("process.wait_http URL must not contain credentials, query parameters, or a fragment.");
            }
            return endpoint;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("process.wait_http requires a valid absolute HTTP URL.");
        }
    }

    private static int boundedHttpWaitMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_HTTP_WAIT_MILLIS;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < MIN_HTTP_WAIT_MILLIS || parsed > MAX_HTTP_WAIT_MILLIS) {
                throw new IllegalArgumentException("timeoutMs must be between " + MIN_HTTP_WAIT_MILLIS + " and " + MAX_HTTP_WAIT_MILLIS + ".");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("timeoutMs must be an integer.");
        }
    }

    /** 未指定 expectedStatus 时，任何 2xx 都代表本机服务已完成 HTTP 就绪。 */
    private static Integer expectedHttpStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 100 || parsed > 599) {
                throw new IllegalArgumentException("expectedStatus must be an HTTP status code between 100 and 599.");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("expectedStatus must be an HTTP status code.");
        }
    }

    private static boolean isExpectedStatus(int status, Integer expectedStatus) {
        return expectedStatus == null ? status >= 200 && status < 300 : status == expectedStatus;
    }

    /**
     * 结果只保留安全 URL、状态码和时序元数据；不包含任何请求头、Cookie、响应头、正文或异常文本。
     */
    private static Map<String, Object> readinessSnapshot(
            URI endpoint,
            ManagedProcess managed,
            int attempts,
            Instant started,
            Integer lastStatus,
            Integer expectedStatus,
            boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processId", managed.id());
        result.put("url", endpoint.toString());
        result.put("ready", ready);
        result.put("attempts", attempts);
        result.put("elapsedMs", Duration.between(started, Instant.now()).toMillis());
        result.put("processActive", managed.process().isAlive());
        if (lastStatus != null) {
            result.put("statusCode", lastStatus);
        }
        if (expectedStatus != null) {
            result.put("expectedStatus", expectedStatus);
        }
        return result;
    }

    private static List<String> shellCommand(String command) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    private static void terminateTree(Process process, boolean forcibly) {
        List<ProcessHandle> descendants = new ArrayList<>(process.toHandle().descendants().toList());
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

    private static String value(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? null : value.toString();
    }

    private static int boundedLogChars(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LOG_CHARS;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1 || parsed > MAX_LOG_CHARS) {
                throw new IllegalArgumentException("maxChars must be between 1 and " + MAX_LOG_CHARS + ".");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("maxChars must be an integer.");
        }
    }

    /** 只从日志文件尾部读取少量 UTF-8 字节，避免长时间运行的服务日志被整文件加载到内存。 */
    private static TailLog readTail(Path path, int maxChars) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new TailLog("", false);
        }
        long size = Files.size(path);
        long maxBytes = Math.min(size, Math.max(4L, (long) maxChars * 4L + 4L));
        ByteBuffer buffer = ByteBuffer.allocate((int) maxBytes);
        try (FileChannel channel = FileChannel.open(path)) {
            channel.position(Math.max(0L, size - maxBytes));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // 文件可能正被进程追加；继续读取直到本次尾部缓冲区填满或到达 EOF。
            }
        }
        String content = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        boolean truncated = size > maxChars || content.length() > maxChars;
        if (content.length() > maxChars) {
            content = content.substring(content.length() - maxChars);
        }
        return new TailLog(content, truncated);
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record ManagedProcess(
            String id,
            String command,
            Path cwd,
            Path stdout,
            Path stderr,
            Process process,
            Instant startedAt) {
    }

    private record TailLog(String content, boolean truncated) {
    }
}
