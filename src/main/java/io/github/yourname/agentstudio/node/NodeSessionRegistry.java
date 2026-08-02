package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 在线节点会话和等待中调用的内存索引。
 *
 * <p>这里的 Future 只是当前进程的等待机制，不是任务事实来源。调用事实已经保存在数据库，节点也会
 * 保存 Journal；控制面重启或断线后应通过 tool.status 对账，而不是重新执行有副作用的工具。
 */
@Component
public class NodeSessionRegistry {

    private static final Duration STATUS_RECONCILIATION_GRACE = Duration.ofSeconds(2);

    private final Map<String, ConnectedSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingInvocation> pendingInvocations = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public NodeSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String nodeId, WebSocketSession socket, String sessionId, long fencingToken) {
        ConnectedSession next = new ConnectedSession(socket, sessionId, fencingToken);
        ConnectedSession previous = sessions.put(nodeId, next);
        if (previous != null && previous.socket() != socket) {
            closeQuietly(previous.socket(), "superseded by a newer node session");
            completeUnknown(previous, "Node session was superseded before the invocation result was confirmed.");
        }
    }

    /** 兼容少量旧测试；生产握手始终传入数据库生成的 fencing token。 */
    void register(String nodeId, WebSocketSession socket) {
        register(nodeId, socket, "session_legacy", 0);
    }

    public void unregister(String nodeId, WebSocketSession socket) {
        ConnectedSession connected = sessions.get(nodeId);
        if (connected == null || connected.socket() != socket || !sessions.remove(nodeId, connected)) {
            return;
        }
        completeUnknown(connected, "Node disconnected before the invocation result was confirmed.");
    }

    public boolean isConnected(String nodeId) {
        ConnectedSession connected = sessions.get(nodeId);
        return connected != null && connected.socket().isOpen();
    }

    public void disconnect(String nodeId, String reason) {
        ConnectedSession connected = sessions.remove(nodeId);
        if (connected == null) {
            return;
        }
        completeUnknown(connected, "Node connection was closed before the invocation result was confirmed.");
        closeQuietly(connected.socket(), reason == null ? "node disconnected" : reason);
    }

    /**
     * 下发数据库中已经存在的 invocationId。超时后先发状态查询；仍无法确认时返回 UNKNOWN。
     */
    public NodeToolCallResult invoke(String nodeId, NodeInvocationDispatch dispatch, Duration timeout) {
        ConnectedSession connected = requireConnected(nodeId);
        CompletableFuture<NodeToolCallResult> future = new CompletableFuture<>();
        PendingInvocation pending = new PendingInvocation(
                nodeId,
                connected.sessionId(),
                connected.fencingToken(),
                dispatch.toolName(),
                dispatch.argumentsDigest(),
                dispatch.attempt(),
                future);
        PendingInvocation existing = pendingInvocations.putIfAbsent(dispatch.invocationId(), pending);
        if (existing != null) {
            throw new IllegalStateException("Invocation is already waiting for a result: " + dispatch.invocationId());
        }

        try {
            send(connected, "tool.invoke", dispatch.invocationId(), dispatch.payload(), dispatch.deadlineAt(), dispatch.traceId());
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException timeoutException) {
                // 超时不代表节点没有执行。先查询 Journal，给终态结果一个很短的回传窗口。
                send(connected, "tool.status", dispatch.invocationId(), Map.of(
                        "invocationId", dispatch.invocationId(),
                        "toolName", dispatch.toolName(),
                        "argumentsDigest", dispatch.argumentsDigest(),
                        "attempt", dispatch.attempt()),
                        Instant.now().plus(STATUS_RECONCILIATION_GRACE), dispatch.traceId());
                try {
                    return future.get(STATUS_RECONCILIATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException unresolved) {
                    return unknown(dispatch, nodeId, "Invocation timed out and node status could not be confirmed.");
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return unknown(dispatch, nodeId, "Invocation wait was interrupted before the result was confirmed.");
        } catch (IllegalArgumentException ex) {
            // JSON 序列化和消息预算在本机即可确定，没有发生远程副作用，不应写成 UNKNOWN。
            throw new IllegalStateException(ex.getMessage(), ex);
        } catch (Exception ex) {
            return unknown(dispatch, nodeId, "Invocation transport failed before the result was confirmed: " + safeMessage(ex));
        } finally {
            pendingInvocations.remove(dispatch.invocationId(), pending);
        }
    }

    /**
     * Compatibility path for the currently deployed node client (protocol 1.0).
     * New durable dispatches use the envelope-based overload above; keeping this
     * adapter avoids breaking an already registered local executor during upgrade.
     */
    public NodeToolCallResult invoke(
            String nodeId,
            String toolName,
            Map<String, Object> arguments,
            Duration timeout) {
        return invoke(nodeId, toolName, arguments, timeout, null);
    }

    public NodeToolCallResult invoke(
            String nodeId,
            String toolName,
            Map<String, Object> arguments,
            Duration timeout,
            String executionSessionId) {
        ConnectedSession connected = requireConnected(nodeId);
        String invocationId = "nodecall_" + UUID.randomUUID();
        CompletableFuture<NodeToolCallResult> future = new CompletableFuture<>();
        PendingInvocation pending = new PendingInvocation(
                nodeId,
                connected.sessionId(),
                connected.fencingToken(),
                toolName,
                "legacy",
                1,
                future);
        pendingInvocations.put(invocationId, pending);
        try {
            sendLegacyInvoke(connected, invocationId, toolName, arguments, executionSessionId);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new NodeToolCallResult(invocationId, nodeId, toolName, "UNKNOWN", null,
                    "Invocation wait was interrupted before the result was confirmed.");
        } catch (java.util.concurrent.TimeoutException ex) {
            return new NodeToolCallResult(invocationId, nodeId, toolName, "UNKNOWN", null,
                    "Invocation timed out before the result was confirmed.");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        } catch (Exception ex) {
            return new NodeToolCallResult(invocationId, nodeId, toolName, "FAILED", null,
                    "Invocation transport failed: " + safeMessage(ex));
        } finally {
            pendingInvocations.remove(invocationId, pending);
        }
    }

    /** 给当前节点活动调用发送协作式取消；ACK 只说明节点收到请求。 */
    public boolean cancel(String nodeId, String invocationId, String traceId) {
        ConnectedSession connected = sessions.get(nodeId);
        if (connected == null || !connected.socket().isOpen()) {
            return false;
        }
        try {
            send(connected, "tool.cancel", invocationId, Map.of("invocationId", invocationId),
                    Instant.now().plusSeconds(30), traceId);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 服务端发出的心跳 ACK、能力 ACK 等也必须经过统一 Envelope。 */
    public void sendControl(String nodeId, String type, String correlationId, Object payload) throws Exception {
        send(requireConnected(nodeId), type, correlationId, payload, null, null);
    }

    /** 校验消息确实来自当前连接，并拒绝旧连接迟到或乱序的帧。 */
    public boolean acceptInbound(
            String nodeId,
            WebSocketSession socket,
            NodeProtocolEnvelope envelope) {
        ConnectedSession connected = sessions.get(nodeId);
        if (connected == null
                || connected.socket() != socket
                || !NodeProtocolEnvelope.CURRENT_VERSION.equals(envelope.protocolVersion())
                || !connected.sessionId().equals(envelope.sessionId())
                || connected.fencingToken() != envelope.fencingToken()
                || envelope.messageId() == null
                || envelope.messageId().isBlank()
                || envelope.sequence() <= 0
                || envelope.expired(Instant.now())) {
            return false;
        }
        long previous = connected.inboundSequence().get();
        return envelope.sequence() > previous
                && connected.inboundSequence().compareAndSet(previous, envelope.sequence());
    }

    /**
     * 完成等待中的调用前再次核对节点、会话、工具、attempt 和参数摘要。
     */
    public boolean complete(
            NodeProtocolEnvelope envelope,
            String nodeId,
            String toolName,
            String argumentsDigest,
            int attempt,
            NodeToolCallResult result) {
        PendingInvocation pending = pendingInvocations.get(result.invocationId());
        if (pending == null
                || !pending.nodeId().equals(nodeId)
                || !pending.sessionId().equals(envelope.sessionId())
                || pending.fencingToken() != envelope.fencingToken()
                || !pending.toolName().equals(toolName)
                || !java.util.Objects.equals(pending.argumentsDigest(), argumentsDigest)
                || pending.attempt() != attempt) {
            return false;
        }
        return pending.future().complete(result);
    }

    /** Accepts results from protocol 1.0 node clients that do not carry envelope metadata. */
    public boolean complete(NodeToolCallResult result) {
        if (result == null || result.invocationId() == null) {
            return false;
        }
        PendingInvocation pending = pendingInvocations.get(result.invocationId());
        return pending != null
                && pending.nodeId().equals(result.nodeId())
                && pending.toolName().equals(result.toolName())
                && pending.future().complete(result);
    }

    ConnectedSession session(String nodeId) {
        return sessions.get(nodeId);
    }

    private void send(
            ConnectedSession connected,
            String type,
            String correlationId,
            Object payload,
            Instant expiresAt,
            String traceId) throws Exception {
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                NodeProtocolEnvelope.CURRENT_VERSION,
                type,
                "msg_" + UUID.randomUUID(),
                connected.sessionId(),
                connected.outboundSequence().incrementAndGet(),
                correlationId,
                Instant.now(),
                expiresAt,
                traceId,
                connected.fencingToken(),
                objectMapper.valueToTree(payload == null ? Map.of() : payload));
        String json = objectMapper.writeValueAsString(envelope);
        if (json.getBytes(StandardCharsets.UTF_8).length > NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Node protocol payload exceeds the control message size limit.");
        }
        synchronized (connected.socket()) {
            connected.socket().sendMessage(new TextMessage(json));
        }
    }

    private void sendLegacyInvoke(
            ConnectedSession connected,
            String invocationId,
            String toolName,
            Map<String, Object> arguments,
            String executionSessionId) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "tool.invoke");
        payload.put("invocationId", invocationId);
        payload.put("toolName", toolName);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        if (executionSessionId != null && !executionSessionId.isBlank()) {
            payload.put("executionSessionId", executionSessionId);
        }
        String json = objectMapper.writeValueAsString(payload);
        if (json.getBytes(StandardCharsets.UTF_8).length > NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Node protocol payload exceeds the control message size limit.");
        }
        synchronized (connected.socket()) {
            connected.socket().sendMessage(new TextMessage(json));
        }
    }

    private ConnectedSession requireConnected(String nodeId) {
        ConnectedSession connected = sessions.get(nodeId);
        if (connected == null || !connected.socket().isOpen()) {
            throw new IllegalArgumentException("Node is not connected: " + nodeId);
        }
        return connected;
    }

    private void completeUnknown(ConnectedSession connected, String message) {
        pendingInvocations.forEach((invocationId, pending) -> {
            if (pending.sessionId().equals(connected.sessionId())
                    && pending.fencingToken() == connected.fencingToken()) {
                pending.future().complete(new NodeToolCallResult(
                        invocationId,
                        pending.nodeId(),
                        pending.toolName(),
                        "UNKNOWN",
                        null,
                        message));
            }
        });
    }

    private static NodeToolCallResult unknown(
            NodeInvocationDispatch dispatch,
            String nodeId,
            String message) {
        return new NodeToolCallResult(
                dispatch.invocationId(), nodeId, dispatch.toolName(), "UNKNOWN", null, message);
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null
                ? error.getCause()
                : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void closeQuietly(WebSocketSession session, String reason) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(new CloseStatus(1008, reason));
        } catch (Exception ignored) {
            // 注册表已经移除连接；底层关闭失败不能恢复旧 session 的授权。
        }
    }

    record ConnectedSession(
            WebSocketSession socket,
            String sessionId,
            long fencingToken,
            AtomicLong outboundSequence,
            AtomicLong inboundSequence) {

        ConnectedSession(WebSocketSession socket, String sessionId, long fencingToken) {
            this(socket, sessionId, fencingToken, new AtomicLong(), new AtomicLong());
        }
    }

    private record PendingInvocation(
            String nodeId,
            String sessionId,
            long fencingToken,
            String toolName,
            String argumentsDigest,
            int attempt,
            CompletableFuture<NodeToolCallResult> future) {
    }
}
