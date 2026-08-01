package io.github.yourname.agentstudio.nodeclient.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.NodeConfig;
import io.github.yourname.agentstudio.nodeclient.SystemInfo;
import io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolLimits;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolRegistry;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolResultBudget;
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

/**
 * 节点 WebSocket 客户端。
 *
 * <p>节点主动连接后端，连接建立后：
 * 1. 等待后端 accepted；
 * 2. 上报 capabilities；
 * 3. 定时发送 heartbeat。
 */
public class NodeWebSocketClient implements WebSocket.Listener {

    // 协议顺序：node.accepted -> capabilities + heartbeat；tool.invoke -> tool.result。

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
    private volatile WebSocket webSocket;
    private volatile CountDownLatch disconnected = new CountDownLatch(1);
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;
    private volatile boolean stopping;

    public NodeWebSocketClient(ObjectMapper objectMapper, HttpClient httpClient, NodeConfig config, SystemInfo systemInfo) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.config = config;
        this.systemInfo = systemInfo;
        this.toolRegistry = new ToolRegistry(httpClient, workspaceRoot(config), config.resolvedAccessMode());
        this.resultBudget = new ToolResultBudget(objectMapper);
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
                        .header("X-Agent-Studio-Node-Id", config.nodeId())
                        .header("Authorization", "Bearer " + config.nodeSecret())
                        .buildAsync(uri, this)
                        .join();
                retrySeconds = 1;
                disconnected.await();
            } catch (Exception ex) {
                System.err.println("WebSocket connection failed: " + ex.getMessage());
            }
            if (!stopping) {
                System.out.println("Reconnecting in " + retrySeconds + " seconds.");
                Thread.sleep(TimeUnit.SECONDS.toMillis(retrySeconds));
                retrySeconds = Math.min(retrySeconds * 2, 30);
            }
        }
        toolRegistry.close();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
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
        try {
            var root = objectMapper.readTree(append.message());
            String type = root.path("type").asText("");
            logReceivedSummary(root, type);
            if ("node.accepted".equals(type)) {
                // 认证通过后才上报本机工具能力，避免未认证连接泄露能力列表。
                sendCapabilities();
                startHeartbeat();
            } else if ("tool.invoke".equals(type)) {
                String invocationId = root.path("invocationId").asText();
                String toolName = root.path("toolName").asText();
                String executionSessionId = root.path("executionSessionId").asText(null);
                Map<String, Object> arguments = objectMapper.convertValue(root.path("arguments"), Map.class);
                toolExecutor.submit(() -> {
                    try {
                        handleToolInvoke(invocationId, toolName, arguments, executionSessionId);
                    } catch (Exception ex) {
                        System.err.println("Tool invocation failed: invocationId=" + safeLogToken(invocationId)
                                + ", tool=" + safeLogToken(toolName));
                    }
                });
            }
        } catch (Exception ex) {
            System.err.println("Failed to handle server message: invalid protocol payload.");
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("WebSocket closed: " + statusCode + " " + reason);
        cancelHeartbeat();
        disconnected.countDown();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("WebSocket error: " + error.getMessage());
        cancelHeartbeat();
        disconnected.countDown();
    }

    private void startHeartbeat() {
        // 心跳失败只记日志，连接层最终会通过 onError/onClose 收敛状态。
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                send(heartbeatPayload());
            } catch (Exception ex) {
                System.err.println("Heartbeat failed: " + ex.getMessage());
            }
        }, 0, 20, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        var task = heartbeatTask;
        if (task != null) {
            task.cancel(true);
            heartbeatTask = null;
        }
    }

    private void sendCapabilities() throws Exception {
        var capabilities = toolRegistry.capabilities();
        var runtimes = toolRegistry.runtimeVersions();
        var features = toolRegistry.features();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "node.capabilities");
        payload.put("timestamp", Instant.now().toString());
        payload.put("capabilityRevision", capabilityRevision(capabilities, runtimes, features));
        payload.put("runtimes", runtimes);
        payload.put("features", features);
        payload.put("capabilities", capabilities);
        send(payload);
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

    private void handleToolInvoke(
            String invocationId,
            String toolName,
            Map<String, Object> arguments,
            String executionSessionId) throws Exception {
        // 原样带回 invocationId，服务端才能完成正确的 Future。
        var execution = resultBudget.apply(toolRegistry.execute(
                toolName,
                arguments == null ? Collections.emptyMap() : arguments,
                executionSessionId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool.result");
        payload.put("timestamp", Instant.now().toString());
        payload.put("invocationId", invocationId);
        payload.put("toolName", toolName);
        payload.put("status", execution.success() ? "SUCCEEDED" : "FAILED");
        payload.put("result", execution.result());
        if (execution.errorMessage() != null) {
            payload.put("errorMessage", execution.errorMessage());
        }
        send(payload);
        System.out.println("Sent tool.result: invocationId=" + safeLogToken(invocationId)
                + ", tool=" + safeLogToken(toolName)
                + ", status=" + (execution.success() ? "SUCCEEDED" : "FAILED"));
    }

    private Map<String, Object> heartbeatPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "node.heartbeat");
        payload.put("timestamp", Instant.now().toString());
        payload.put("hostname", systemInfo.hostname());
        payload.put("osName", systemInfo.osName());
        payload.put("osArch", systemInfo.osArch());
        payload.put("clientVersion", systemInfo.clientVersion());
        return payload;
    }

    private void send(Object payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
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
                    null,
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
