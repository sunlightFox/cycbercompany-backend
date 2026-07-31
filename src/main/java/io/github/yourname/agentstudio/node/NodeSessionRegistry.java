package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 在线节点 WebSocket 会话注册表。
 *
 * <p>后端 REST/API 层不直接持有 WebSocketSession，而是通过这个注册表下发 tool.invoke。
 * 这样后续要把调用记录持久化、加审批、加取消，都可以继续围绕 invocationId 扩展。
 */
@Component
// 学习提示：本类将“节点连接”和“等待中的工具调用”分别按 nodeId、invocationId 管理。
// REST 调用创建 Future 并发送 tool.invoke；收到 tool.result 时 complete() 唤醒对应调用。
public class NodeSessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<NodeToolCallResult>> pendingInvocations = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public NodeSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String nodeId, WebSocketSession session) {
        sessions.put(nodeId, session);
    }

    public void unregister(String nodeId, WebSocketSession session) {
        sessions.remove(nodeId, session);
    }

    public boolean isConnected(String nodeId) {
        WebSocketSession session = sessions.get(nodeId);
        return session != null && session.isOpen();
    }

    public NodeToolCallResult invoke(String nodeId, String toolName, Map<String, Object> arguments, Duration timeout) {
        return invoke(nodeId, toolName, arguments, timeout, null);
    }

    /** Supplies backend-owned execution metadata to stateful node tools. */
    public NodeToolCallResult invoke(
            String nodeId,
            String toolName,
            Map<String, Object> arguments,
            Duration timeout,
            String executionSessionId) {
        WebSocketSession session = sessions.get(nodeId);
        if (session == null || !session.isOpen()) {
            throw new IllegalArgumentException("Node is not connected: " + nodeId);
        }
        // messageId 标识传输消息；invocationId 标识一次可等待、可审计的工具执行。
        String invocationId = "nodeinv_" + UUID.randomUUID();
        String messageId = "msg_" + UUID.randomUUID();
        CompletableFuture<NodeToolCallResult> future = new CompletableFuture<>();
        // 先注册再发送，避免节点极快回包时找不到等待 Future 的竞争条件。
        pendingInvocations.put(invocationId, future);
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "tool.invoke");
            payload.put("messageId", messageId);
            payload.put("invocationId", invocationId);
            payload.put("toolName", toolName);
            payload.put("arguments", arguments == null ? Map.of() : arguments);
            if (executionSessionId != null && !executionSessionId.isBlank()) {
                payload.put("executionSessionId", executionSessionId);
            }
            synchronized (session) {
                // 同一 WebSocket 的发送串行化，避免并发写入时帧交错。
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (Exception ex) {
            throw new IllegalStateException("Node tool invocation failed: " + ex.getMessage(), ex);
        } finally {
            // 成功、超时和异常都必须清理，否则等待表会不断增长。
            pendingInvocations.remove(invocationId);
        }
    }

    public void complete(NodeToolCallResult result) {
        // Future 不存在意味着调用已超时或回包已经过期，可安全忽略。
        CompletableFuture<NodeToolCallResult> future = pendingInvocations.get(result.invocationId());
        if (future != null) {
            future.complete(result);
        }
    }
}
