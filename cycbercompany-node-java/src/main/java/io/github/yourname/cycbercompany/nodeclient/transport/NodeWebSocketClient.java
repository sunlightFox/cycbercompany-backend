package io.github.yourname.cycbercompany.nodeclient.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.yourname.cycbercompany.nodeclient.NodeConfig;
import io.github.yourname.cycbercompany.nodeclient.SystemInfo;
import io.github.yourname.cycbercompany.nodeclient.artifact.ArtifactUploadClient;
import io.github.yourname.cycbercompany.nodeclient.protocol.NodeProtocolLimits;
import io.github.yourname.cycbercompany.nodeclient.protocol.NodeInvocationJournal;
import io.github.yourname.cycbercompany.nodeclient.protocol.NodeJournalEntry;
import io.github.yourname.cycbercompany.nodeclient.protocol.NodeProtocolEnvelope;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolRegistry;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolResultBudget;
import io.github.yourname.cycbercompany.nodeclient.skill.DockerSkillRuntime;
import io.github.yourname.cycbercompany.nodeclient.skill.SkillBundleCache;
import io.github.yourname.cycbercompany.nodeclient.skill.SkillTool;
import io.github.yourname.cycbercompany.nodeclient.skill.SkillWorkspaceManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 节点 WebSocket 客户端。
 *
 * <p>节点主动连接后端，连接建立后：
 * 1. 等待后端 accepted；
 * 2. 上报 capabilities；
 * 3. 定时发送 heartbeat。
 */
public class NodeWebSocketClient implements WebSocket.Listener {

    // 协议顺序：node.accepted -> capabilities + heartbeat；tool.invoke -> accepted/progress/result。
    private static final String NODE_PROTOCOL_SUBPROTOCOL = "cycbercompany-node-v1.1";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final NodeConfig config;
    private final SystemInfo systemInfo;
    private final ToolRegistry toolRegistry;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object sendLock = new Object();
    private final BoundedTextMessageAccumulator incomingMessage =
            new BoundedTextMessageAccumulator(NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES);
    private final ToolResultBudget resultBudget;
    private final ArtifactUploadClient artifactUploader;
    private final NodeInvocationJournal journal;
    private final Map<String, Future<?>> activeInvocations = new ConcurrentHashMap<>();
    /**
     * 调用 ID 与 Run 会话的短暂关联。它仅在调用尚未结束时存在，用于让取消帧可以立即
     * 找到应释放的浏览器，而不会把同一节点上其他 Run 的浏览器一并关闭。
     */
    private final Map<String, String> activeExecutionSessions = new ConcurrentHashMap<>();
    private final AtomicLong outboundSequence = new AtomicLong();
    private final AtomicLong inboundSequence = new AtomicLong();
    private volatile WebSocket webSocket;
    private volatile CountDownLatch disconnected = new CountDownLatch(1);
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;
    private volatile boolean stopping;
    private volatile String sessionId;
    private volatile long fencingToken;
    private volatile long heartbeatIntervalSeconds = 20;
    private volatile Consumer<Boolean> connectionObserver = ignored -> { };

    public NodeWebSocketClient(ObjectMapper objectMapper, HttpClient httpClient, NodeConfig config, SystemInfo systemInfo) {
        this(objectMapper, httpClient, config, systemInfo, null);
    }

    public NodeWebSocketClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            NodeConfig config,
            SystemInfo systemInfo,
            Path desktopRoot) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.config = config;
        this.systemInfo = systemInfo;
        Path nodeData = defaultNodeDataRoot();
        Path artifactRoot = nodeData.resolve("artifacts");
        SkillTool skillTool = new SkillTool(
                new SkillBundleCache(
                        httpClient,
                        config.serverUrl(),
                        config.nodeId(),
                        config.nodeSecret(),
                        nodeData.resolve("skill-cache")),
                new SkillWorkspaceManager(nodeData.resolve("run-workspaces")),
                DockerSkillRuntime.fromEnvironment());
        this.toolRegistry = desktopRoot == null
                ? new ToolRegistry(httpClient, workspaceRoot(config), config.resolvedAccessMode(), skillTool, artifactRoot,
                        systemInfo.osName())
                : new ToolRegistry(
                        httpClient, workspaceRoot(config), config.resolvedAccessMode(), desktopRoot, skillTool, artifactRoot,
                        systemInfo.osName());
        this.resultBudget = new ToolResultBudget(objectMapper);
        this.artifactUploader = new ArtifactUploadClient(
                objectMapper, httpClient, config.serverUrl(), config.nodeId(), config.nodeSecret(), artifactRoot);
        this.journal = new NodeInvocationJournal(objectMapper, nodeData.resolve("journal"));
    }

    public void startBlocking() throws Exception {
        // 命令行程序保持前台运行，直到连接关闭，便于服务管理工具观察节点状态。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 先停止心跳，再发送关闭帧，降低服务端长期显示 ONLINE 的概率。
            stopping = true;
            cancelHeartbeat();
            toolRegistry.close();
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "node shutdown");
            }
        }));
        int retrySeconds = 1;
        while (!stopping) {
            disconnected = new CountDownLatch(1);
            try {
                URI uri = websocketUri();
                System.out.println("Connecting to " + safeServerAddress(uri));
                this.webSocket = httpClient.newWebSocketBuilder()
                        .header("X-CycberCompany-Node-Id", config.nodeId())
                        .header("Authorization", "Bearer " + config.nodeSecret())
                        // 子协议是显式版本协商，不能依赖 User-Agent 或能力字段猜测服务端消息格式。
                        .subprotocols(NODE_PROTOCOL_SUBPROTOCOL)
                        .buildAsync(uri, this)
                        .join();
                retrySeconds = 1;
                disconnected.await();
            } catch (Exception ex) {
                System.err.println("WebSocket connection failed: " + ex.getMessage());
                notifyConnectionObserver(false);
            }
            if (!stopping) {
                System.out.println("Reconnecting in " + retrySeconds + " seconds.");
                Thread.sleep(TimeUnit.SECONDS.toMillis(retrySeconds));
                retrySeconds = Math.min(retrySeconds * 2, 30);
            }
        }
        toolRegistry.close();
    }

    /**
     * Reports whether the server has accepted this node connection. Opening a WebSocket alone is
     * not sufficient: the server must first authenticate the node and send {@code node.accepted}.
     */
    public void setConnectionObserver(Consumer<Boolean> observer) {
        this.connectionObserver = observer == null ? ignored -> { } : observer;
    }

    /** Stops reconnects and releases the active node connection and local tool resources. */
    public void stop() {
        stopping = true;
        notifyConnectionObserver(false);
        cancelHeartbeat();
        WebSocket socket = webSocket;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "node stopped by user");
            } catch (Exception ignored) {
            }
        }
        disconnected.countDown();
        closeClientResources();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        incomingMessage.clear();
        this.sessionId = null;
        this.fencingToken = 0;
        this.heartbeatIntervalSeconds = 20;
        this.outboundSequence.set(0);
        this.inboundSequence.set(0);
        System.out.println("WebSocket opened.");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        BoundedTextMessageAccumulator.AppendResult append = incomingMessage.append(data, last);
        if (append.status() == BoundedTextMessageAccumulator.Status.TOO_LARGE) {
            System.err.println("Closing WebSocket because a protocol message exceeded the size limit.");
            cancelHeartbeat();
            webSocket.sendClose(1009, "protocol message too large");
            return null;
        }
        if (append.status() == BoundedTextMessageAccumulator.Status.INCOMPLETE) {
            webSocket.request(1);
            return null;
        }
        NodeProtocolEnvelope envelope;
        try {
            envelope = objectMapper.readValue(append.message(), NodeProtocolEnvelope.class);
        } catch (Exception ex) {
            closeForProtocolError(webSocket, "invalid protocol JSON");
            return null;
        }
        if (envelope == null || envelope.type() == null || envelope.type().isBlank()) {
            closeForProtocolError(webSocket, "invalid protocol envelope");
            return null;
        }
        try {
            String type = envelope.type();
            if (!acceptEnvelope(envelope, "node.accepted".equals(type))) {
                System.err.println("Ignoring stale or invalid node protocol envelope.");
                webSocket.request(1);
                return null;
            }
            JsonNode payload = envelope.payload() == null ? MissingNode.getInstance() : envelope.payload();
            logReceivedSummary(payload, type);
            if ("node.accepted".equals(type)) {
                // 认证通过后才上报本机工具能力，避免未认证连接泄露能力列表。
                this.sessionId = envelope.sessionId();
                this.fencingToken = envelope.fencingToken();
                this.heartbeatIntervalSeconds = Math.max(1, payload.path("heartbeatIntervalSeconds").asLong(20));
                sendCapabilities();
                startHeartbeat();
                notifyConnectionObserver(true);
            } else if ("tool.invoke".equals(type)) {
                handleDispatch(payload, envelope.traceId());
            } else if ("tool.status".equals(type)) {
                sendStatus(payload.path("invocationId").asText(), envelope.traceId());
            } else if ("tool.cancel".equals(type)) {
                cancelInvocation(payload.path("invocationId").asText(), envelope.traceId());
            } else if ("node.shutdown".equals(type)) {
                shutdownFromServer(webSocket, payload.path("reason").asText("server requested shutdown"));
            }
        } catch (Exception ex) {
            System.err.println("Failed to handle server message: invalid protocol payload.");
        }
        webSocket.request(1);
        return null;
    }

    private void closeForProtocolError(WebSocket socket, String reason) {
        System.err.println("Closing WebSocket because the server sent " + reason + ".");
        cancelHeartbeat();
        incomingMessage.clear();
        try {
            socket.sendClose(1007, reason);
        } catch (Exception ignored) {
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("WebSocket closed: " + statusCode + " " + reason);
        cancelHeartbeat();
        incomingMessage.clear();
        notifyConnectionObserver(false);
        disconnected.countDown();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("WebSocket error: " + error.getMessage());
        cancelHeartbeat();
        incomingMessage.clear();
        notifyConnectionObserver(false);
        disconnected.countDown();
    }

    private void startHeartbeat() {
        // 心跳失败只记日志，连接层最终会通过 onError/onClose 收敛状态。
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                sendEnvelope("node.heartbeat", null, heartbeatPayload(), null, null);
            } catch (Exception ex) {
                System.err.println("Heartbeat failed: " + ex.getMessage());
            }
        }, 0, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        var task = heartbeatTask;
        if (task != null) {
            task.cancel(true);
            heartbeatTask = null;
        }
    }

    private void shutdownFromServer(WebSocket socket, String reason) {
        System.out.println("Server requested node shutdown.");
        stopping = true;
        notifyConnectionObserver(false);
        closeClientResources();
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE,
                    reason == null || reason.isBlank() ? "server requested shutdown" : reason);
        } catch (Exception ignored) {
        }
        disconnected.countDown();
    }

    private void closeClientResources() {
        cancelHeartbeat();
        activeInvocations.values().forEach(future -> future.cancel(true));
        activeInvocations.clear();
        toolRegistry.close();
        scheduler.shutdownNow();
        toolExecutor.shutdownNow();
    }

    private void notifyConnectionObserver(boolean connected) {
        try {
            connectionObserver.accept(connected);
        } catch (RuntimeException ignored) {
            // A desktop status observer must never disrupt the node protocol loop.
        }
    }

    private void sendCapabilities() throws Exception {
        var capabilities = toolRegistry.capabilities();
        var runtimes = toolRegistry.runtimeVersions();
        var features = toolRegistry.features();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capabilityRevision", capabilityRevision(capabilities, runtimes, features));
        payload.put("runtimes", runtimes);
        payload.put("features", features);
        payload.put("capabilities", capabilities);
        sendEnvelope("node.capabilities", null, payload, null, null);
    }

    private String capabilityRevision(Object capabilities, Object runtimes, Object features) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("capabilities", capabilities);
            snapshot.put("runtimes", runtimes);
            snapshot.put("features", features);
            byte[] bytes = objectMapper.writeValueAsBytes(snapshot);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Unable to serialize the node capability snapshot.", ex);
        }
    }

    private void handleDispatch(JsonNode payload, String traceId) throws Exception {
        String invocationId = payload.path("invocationId").asText();
        String toolName = payload.path("toolName").asText();
        String argumentsDigest = payload.path("argumentsDigest").asText();
        int attempt = payload.path("attempt").asInt(1);
        String executionSessionId = payload.path("executionSessionId").asText(null);
        Map<String, Object> arguments = mapOrEmpty(payload.path("arguments"));
        if (invocationId == null || invocationId.isBlank()) {
            System.err.println("Ignoring malformed tool.invoke without an invocationId.");
            return;
        }
        if (toolName == null || toolName.isBlank()) {
            sendInvocationResult(null, "FAILED", invocationId,
                    "Malformed tool.invoke: toolName is required.", traceId);
            return;
        }
        if (argumentsDigest == null || argumentsDigest.isBlank()) {
            sendInvocationResult(null, "FAILED", invocationId,
                    "Malformed tool.invoke: argumentsDigest is required.", traceId);
            return;
        }
        NodeInvocationJournal.Acceptance acceptance = journal.accept(invocationId, toolName, argumentsDigest, attempt);
        if (acceptance.decision() == NodeInvocationJournal.Decision.CONFLICT) {
            sendInvocationResult(acceptance.entry(), "FAILED", null,
                    "Invocation ID was reused with different tool arguments or attempt.", traceId);
            return;
        }
        if (acceptance.decision() == NodeInvocationJournal.Decision.CACHED_TERMINAL) {
            sendJournalStatus("tool.result", acceptance.entry(), traceId);
            return;
        }
        if (acceptance.decision() == NodeInvocationJournal.Decision.ALREADY_ACTIVE) {
            sendJournalStatus("tool.status.result", acceptance.entry(), traceId);
            return;
        }
        // accepted 的含义仅是“已持久化”；服务端在收到它后才允许把状态推进到 ACCEPTED。
        // 先登记再回 ACK。这样服务端收到 accepted 后立刻发来 cancel 时，也能准确关闭
        // 当前 Run 的浏览器会话；空 ID 不登记，避免误伤兼容模式下的 default 会话。
        if (executionSessionId != null && !executionSessionId.isBlank()) {
            activeExecutionSessions.put(invocationId, executionSessionId);
        }
        sendJournalStatus("tool.accepted", acceptance.entry(), traceId);
        Future<?> future = toolExecutor.submit(() -> {
            try {
                handleToolInvoke(invocationId, toolName, arguments, executionSessionId, traceId);
            } catch (Exception ex) {
                System.err.println("Tool invocation failed: invocationId=" + safeLogToken(invocationId)
                        + ", tool=" + safeLogToken(toolName));
            } finally {
                activeInvocations.remove(invocationId);
                if (executionSessionId != null && !executionSessionId.isBlank()) {
                    activeExecutionSessions.remove(invocationId, executionSessionId);
                }
            }
        });
        activeInvocations.put(invocationId, future);
        // 虚拟线程可能在 put 前已经完成。补做一次检查，避免 finally 的 remove 先发生而
        // 这里又把已结束的 Future 留在活动表中，造成后续取消命中一条过期记录。
        if (future.isDone()) {
            activeInvocations.remove(invocationId, future);
        }
    }

    private Map<String, Object> mapOrEmpty(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        Map<String, Object> converted = objectMapper.convertValue(
                node, new TypeReference<Map<String, Object>>() { });
        return converted == null ? Map.of() : converted;
    }

    private void handleToolInvoke(
            String invocationId,
            String toolName,
            Map<String, Object> arguments,
            String executionSessionId,
            String traceId) throws Exception {
        NodeJournalEntry started = journal.start(invocationId);
        if ("CANCEL_REQUESTED".equals(started.status())) {
            // 取消在本地工具开始之前已经被持久化。这里必须先写入确定的终态再返回，
            // 不能仅回复 cancel.ack，否则服务端重连时会把一次根本没有开始的调用误判为 UNKNOWN。
            NodeJournalEntry cancelled = journal.finish(
                    invocationId,
                    "CANCELLED",
                    null,
                    "Invocation was cancelled before local execution began.");
            sendJournalStatus("tool.result", cancelled, traceId);
            return;
        }
        sendJournalStatus("tool.progress", started, traceId);
        try {
            // 原样带回 invocationId，服务端才能完成正确的 Future。
            ToolExecutionResult localExecution = toolRegistry.execute(
                    toolName,
                    arguments == null ? Collections.emptyMap() : arguments,
                    executionSessionId);
            if (localExecution == null) {
                localExecution = ToolExecutionResult.failure(
                        "Node tool returned no execution result.");
            }
            // A cancellation can interrupt a tool implementation that reports a normal
            // failure result instead of throwing. Resolve the journal state after the
            // implementation returns so a cancelled command cannot be recorded as FAILED.
            NodeJournalEntry afterExecution = journal.find(invocationId);
            if (afterExecution != null && "CANCEL_REQUESTED".equals(afterExecution.status())) {
                NodeJournalEntry cancelled = journal.finish(
                        invocationId,
                        "CANCELLED",
                        null,
                        "Invocation was cancelled while local execution was in progress.");
                sendJournalStatus("tool.result", cancelled, traceId);
                return;
            }
            // 截图、Trace 等大对象先通过 HTTP 上传；WebSocket 只发送小型 Artifact 引用。
            var execution = resultBudget.apply(artifactUploader.uploadIfPresent(executionSessionId, localExecution));
            NodeJournalEntry finished = journal.finish(
                    invocationId,
                    execution.success() ? "SUCCEEDED" : "FAILED",
                    execution.result(), execution.errorMessage());
            sendJournalStatus("tool.result", finished, traceId);
        } catch (Exception ex) {
            // cancel(true) 导致的中断不是普通工具失败。仅当 Journal 已记录取消请求时，
            // 才能把该异常归类为 CANCELLED；否则仍保留 FAILED，避免掩盖真实错误。
            NodeJournalEntry current = journal.find(invocationId);
            boolean cancellationRequested = current != null
                    && "CANCEL_REQUESTED".equals(current.status());
            NodeJournalEntry finished = journal.finish(
                    invocationId,
                    cancellationRequested ? "CANCELLED" : "FAILED",
                    null,
                    cancellationRequested
                            ? "Invocation was cancelled while local execution was in progress."
                            : safeException(ex));
            sendJournalStatus("tool.result", finished, traceId);
        }
        System.out.println("Sent tool.result: invocationId=" + safeLogToken(invocationId)
                + ", tool=" + safeLogToken(toolName));
    }

    private Map<String, Object> heartbeatPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hostname", systemInfo.hostname());
        payload.put("osName", systemInfo.osName());
        payload.put("osArch", systemInfo.osArch());
        payload.put("clientVersion", systemInfo.clientVersion());
        return payload;
    }

    private void sendEnvelope(
            String type,
            String correlationId,
            Object payload,
            Instant expiresAt,
            String traceId) throws Exception {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("Node protocol session has not been accepted yet.");
        }
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                NodeProtocolEnvelope.CURRENT_VERSION,
                type,
                "msg_" + java.util.UUID.randomUUID(),
                sessionId,
                outboundSequence.incrementAndGet(),
                correlationId,
                Instant.now(),
                expiresAt,
                traceId,
                fencingToken,
                objectMapper.valueToTree(payload == null ? Map.of() : payload));
        String json = objectMapper.writeValueAsString(envelope);
        int payloadBytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Protocol payload exceeds the control message size limit.");
        }
        synchronized (sendLock) {
            webSocket.sendText(json, true).join();
        }
    }

    private static Path workspaceRoot(NodeConfig config) {
        if (config.workspaceRoot() == null || config.workspaceRoot().isBlank()) {
            return null;
        }
        return Path.of(config.workspaceRoot()).toAbsolutePath().normalize();
    }

    private boolean acceptEnvelope(NodeProtocolEnvelope envelope, boolean acceptedMessage) {
        if (!NodeProtocolEnvelope.CURRENT_VERSION.equals(envelope.protocolVersion())
                || envelope.messageId() == null || envelope.messageId().isBlank()
                || envelope.sequence() <= 0 || envelope.expired(Instant.now())) {
            return false;
        }
        if (acceptedMessage) {
            return sessionId == null || (sessionId.equals(envelope.sessionId()) && fencingToken == envelope.fencingToken());
        }
        if (sessionId == null || !sessionId.equals(envelope.sessionId()) || fencingToken != envelope.fencingToken()) {
            return false;
        }
        long before = inboundSequence.get();
        return envelope.sequence() > before && inboundSequence.compareAndSet(before, envelope.sequence());
    }

    private void sendStatus(String invocationId, String traceId) throws Exception {
        NodeJournalEntry entry = journal.find(invocationId);
        if (entry == null) {
            sendInvocationResult(null, "UNKNOWN", invocationId,
                    "Invocation is not present in this node journal.", traceId);
            return;
        }
        sendJournalStatus("tool.status.result", entry, traceId);
    }

    private void cancelInvocation(String invocationId, String traceId) throws Exception {
        NodeJournalEntry entry = journal.find(invocationId);
        if (entry == null) {
            sendEnvelope("tool.cancel.ack", invocationId, Map.of(
                    "invocationId", invocationId,
                    "received", false,
                    "sideEffectsRolledBack", false), Instant.now().plusSeconds(30), traceId);
            return;
        }
        NodeJournalEntry requested = journal.cancelRequested(invocationId);
        // 浏览器关闭在独立虚拟线程中执行。Playwright 的某些等待操作可能正持有浏览器锁，
        // 此处不能阻塞 WebSocket 接收线程；随后仍会中断原工具线程，促使等待尽快退出并让
        // 关闭动作接管资源。ACK 只表示关闭请求已排队，不能声称已经回滚页面副作用。
        boolean browserSessionCloseRequested = requestBrowserSessionClose(invocationId);
        Future<?> future = activeInvocations.get(invocationId);
        boolean interrupted = future != null && future.cancel(true);
        sendEnvelope("tool.cancel.ack", invocationId, Map.of(
                "invocationId", invocationId,
                "received", true,
                "interruptionRequested", interrupted,
                "browserSessionCloseRequested", browserSessionCloseRequested,
                "status", requested.status(),
                "sideEffectsRolledBack", false), Instant.now().plusSeconds(30), traceId);
    }

    /**
     * 为已取消调用安排浏览器清理，而不在协议线程中等待 Playwright 完成。
     *
     * <p>会话值在任务 finally 中被移除前先复制到局部变量，因此即使被中断的工具线程很快
     * 结束，清理任务依旧只会处理正确的 Run 会话。
     */
    private boolean requestBrowserSessionClose(String invocationId) {
        String executionSessionId = activeExecutionSessions.get(invocationId);
        if (executionSessionId == null || executionSessionId.isBlank()) {
            return false;
        }
        try {
            toolExecutor.submit(() -> {
                boolean closed = toolRegistry.closeExecutionSession(executionSessionId);
                if (!closed) {
                    // 会话可能尚未创建，或已由正常收尾释放；两者都是安全的幂等结果。
                    System.out.println("Browser cleanup found no active session for cancelled invocation="
                            + safeLogToken(invocationId));
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            // 节点退出期间线程池可能已关闭。服务端之后的 Run 清理会再次尝试关闭会话。
            return false;
        }
    }

    private void sendJournalStatus(String type, NodeJournalEntry entry, String traceId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invocationId", entry.invocationId());
        payload.put("toolName", entry.toolName());
        payload.put("argumentsDigest", entry.argumentsDigest());
        payload.put("attempt", entry.attempt());
        payload.put("status", entry.status());
        payload.put("result", entry.result());
        if (entry.errorMessage() != null) payload.put("errorMessage", entry.errorMessage());
        sendEnvelope(type, entry.invocationId(), payload, Instant.now().plusSeconds(120), traceId);
    }

    private void sendInvocationResult(
            NodeJournalEntry entry,
            String status,
            String invocationId,
            String errorMessage,
            String traceId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invocationId", entry == null ? invocationId : entry.invocationId());
        payload.put("toolName", entry == null ? "unknown" : entry.toolName());
        payload.put("argumentsDigest", entry == null ? "unknown" : entry.argumentsDigest());
        payload.put("attempt", entry == null ? 1 : entry.attempt());
        payload.put("status", status);
        payload.put("result", entry == null ? null : entry.result());
        payload.put("errorMessage", errorMessage);
        sendEnvelope("tool.result", invocationId, payload, Instant.now().plusSeconds(120), traceId);
    }

    private static String safeException(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static Path defaultNodeDataRoot() {
        return Path.of(System.getProperty("user.home"), ".cycbercompany-node", "data")
                .toAbsolutePath()
                .normalize();
    }

    URI websocketUri() {
        // REST 的 http(s) 地址转换为 WebSocket 所需的 ws(s) 地址。
        String base = config.serverUrl().replace("https://", "wss://").replace("http://", "ws://");
        URI configured = URI.create(base + config.websocketUrl());
        try {
            // 兼容旧配置文件：即使 websocketUrl 曾包含 nodeSecret query，也必须在真正连接前丢弃。
            return new URI(
                    configured.getScheme(),
                    configured.getUserInfo(),
                    configured.getHost(),
                    configured.getPort(),
                    configured.getPath(),
                    "nodeId=" + java.net.URLEncoder.encode(config.nodeId(), StandardCharsets.UTF_8)
                            + "&nodeSecret=" + java.net.URLEncoder.encode(config.nodeSecret(), StandardCharsets.UTF_8),
                    null);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Configured WebSocket URL is invalid.");
        }
    }

    static String safeServerAddress(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    }

    private static void logReceivedSummary(com.fasterxml.jackson.databind.JsonNode root, String type) {
        if ("tool.invoke".equals(type)) {
            System.out.println("Received tool.invoke: invocationId="
                    + safeLogToken(root.path("invocationId").asText(""))
                    + ", tool=" + safeLogToken(root.path("toolName").asText("")));
            return;
        }
        System.out.println("Received protocol message: type=" + safeLogToken(type));
    }

    private static String safeLogToken(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String safe = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }
}
