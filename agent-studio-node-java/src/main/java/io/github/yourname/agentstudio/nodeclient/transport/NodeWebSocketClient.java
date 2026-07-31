package io.github.yourname.agentstudio.nodeclient.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.NodeConfig;
import io.github.yourname.agentstudio.nodeclient.SystemInfo;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
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
    private final StringBuilder incomingMessage = new StringBuilder();
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
                System.out.println("Connecting to " + uri);
                this.webSocket = httpClient.newWebSocketBuilder().buildAsync(uri, this).join();
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
        String message;
        synchronized (incomingMessage) {
            incomingMessage.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            message = incomingMessage.toString();
            incomingMessage.setLength(0);
        }
        System.out.println("Received: " + message);
        try {
            var root = objectMapper.readTree(message);
            String type = root.path("type").asText("");
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
                        System.err.println("Tool invocation failed: " + ex.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            System.err.println("Failed to handle server message: " + ex.getMessage());
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "node.capabilities");
        payload.put("timestamp", Instant.now().toString());
        payload.put("capabilities", toolRegistry.capabilities());
        send(payload);
    }

    private void handleToolInvoke(
            String invocationId,
            String toolName,
            Map<String, Object> arguments,
            String executionSessionId) throws Exception {
        // 原样带回 invocationId，服务端才能完成正确的 Future。
        var execution = toolRegistry.execute(
                toolName,
                arguments == null ? Collections.emptyMap() : arguments,
                executionSessionId);
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
        synchronized (sendLock) {
            webSocket.sendText(objectMapper.writeValueAsString(payload), true).join();
        }
    }

    private static Path workspaceRoot(NodeConfig config) {
        if (config.workspaceRoot() == null || config.workspaceRoot().isBlank()) {
            return null;
        }
        return Path.of(config.workspaceRoot()).toAbsolutePath().normalize();
    }

    private URI websocketUri() {
        // REST 的 http(s) 地址转换为 WebSocket 所需的 ws(s) 地址。
        String base = config.serverUrl().replace("https://", "wss://").replace("http://", "ws://");
        return URI.create(base + config.websocketUrl());
    }
}
