package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 节点 WebSocket 通道。
 *
 * <p>第一版只处理“连接状态”和“能力上报”。真正的 tool.invoke 会在下一阶段加入，
 * 这样可以先把节点接入、在线状态和工具管理这条地基打稳。
 */
@Component
public class NodeChannelWebSocketHandler extends TextWebSocketHandler {

    private static final String NODE_ID_ATTR = "nodeId";
    private static final String SESSION_ID_ATTR = "nodeSessionId";
    static final String NODE_ID_HEADER = "X-Agent-Studio-Node-Id";

    private final NodeService nodes;
    private final NodeSessionRegistry sessions;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public NodeChannelWebSocketHandler(NodeService nodes, NodeSessionRegistry sessions, ObjectMapper objectMapper) {
        this.nodes = nodes;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        NodeHandshakeCredentials credentials = credentials(session.getHandshakeHeaders());
        try {
            NodeConnectionEntity node = nodes.authenticateNode(credentials.nodeId(), credentials.nodeSecret());
            String sessionId = "session_" + java.util.UUID.randomUUID();
            session.getAttributes().put(NODE_ID_ATTR, node.id());
            session.getAttributes().put(SESSION_ID_ATTR, sessionId);
            sessions.register(node.id(), session, sessionId, node.fencingToken());
            sessions.sendControl(node.id(), "node.accepted", null, Map.of(
                    "nodeId", node.id(),
                    "heartbeatIntervalSeconds", 20,
                    "fencingToken", node.fencingToken()));
            // 连接确认后只查询节点本地 journal 的既有记录，严禁把未知副作用重新变成 tool.invoke。
            for (NodeService.NodeInvocationReconciliation request : nodes.reconciliationRequests(node.id(), 100)) {
                if (request.toolName() == null || request.toolName().isBlank()
                        || request.argumentsDigest() == null || request.argumentsDigest().isBlank()
                        || request.attempt() < 1) {
                    continue;
                }
                sessions.sendControl(node.id(), "tool.status", request.invocationId(), Map.of(
                        "invocationId", request.invocationId(),
                        "toolName", request.toolName(),
                        "argumentsDigest", request.argumentsDigest(),
                        "attempt", request.attempt()));
            }
        } catch (DataAccessException ex) {
            // A transient or unrecoverable store fault must not be presented to the node as an
            // authentication/policy failure. The client reconnect loop can recover after the
            // supervised control plane restarts, while a 1008 response falsely suggests that
            // the node has no permission or no tools.
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ex) {
            // 握手失败时没有可信 node session，因此只发送最小拒绝信封后立刻关闭。
            sendUnauthenticated(session, "node.rejected", Map.of("error", "Node authentication failed."));
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String nodeId = (String) session.getAttributes().get(NODE_ID_ATTR);
        if (nodeId == null || nodeId.isBlank()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 容器缓冲区只是传输层保护；解析 JSON 前再按协议预算检查一次，避免配置漂移后失去边界。
        if (message.getPayload().getBytes(StandardCharsets.UTF_8).length
                > NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }

        NodeProtocolEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), NodeProtocolEnvelope.class);
        } catch (Exception ex) {
            session.close(new CloseStatus(1007, "Invalid node protocol JSON."));
            return;
        }
        if (!sessions.acceptInbound(nodeId, session, envelope)) {
            // 旧 fencing token、过期帧、错误 session 或乱序帧都不能影响最新连接。
            return;
        }
        String type = envelope.type();
        JsonNode payload = envelope.payload() == null ? MissingNode.getInstance() : envelope.payload();
        if ("node.heartbeat".equals(type)) {
            nodes.heartbeat(
                    nodeId,
                    textOrNull(payload, "hostname"),
                    textOrNull(payload, "osName"),
                    textOrNull(payload, "osArch"),
                    textOrNull(payload, "clientVersion"));
            sessions.sendControl(nodeId, "node.heartbeat.ack", envelope.messageId(), Map.of());
            return;
        }

        if ("node.capabilities".equals(type)) {
            List<NodeCapabilityPayload> capabilities = listOrEmpty(
                    payload.path("capabilities"),
                    new TypeReference<List<NodeCapabilityPayload>>() {
                    });
            Map<String, String> runtimes = mapOrEmpty(
                    payload.path("runtimes"), new TypeReference<Map<String, String>>() { });
            java.util.Set<String> features = setOrEmpty(
                    payload.path("features"), new TypeReference<java.util.Set<String>>() { });
            List<NodeToolView> saved = nodes.saveCapabilities(
                    nodeId,
                    textOrNull(payload, "capabilityRevision"),
                    runtimes,
                    features,
                    capabilities);
            sessions.sendControl(nodeId, "node.capabilities.ack", envelope.messageId(), Map.of(
                    "toolCount", saved == null ? 0 : saved.size()));
            return;
        }

        if ("tool.accepted".equals(type)) {
            // The dispatch request is synchronously waiting inside the transaction that owns this
            // invocation. Persisting progress here would contend for that same row and can close
            // the WebSocket before the tool result reaches the waiting caller.
            return;
        }

        if ("tool.progress".equals(type)) {
            return;
        }

        if ("tool.result".equals(type) || "tool.status.result".equals(type)) {
            Map<String, Object> result = toolResultMap(payload.path("result"));
            String invocationId = payload.path("invocationId").asText();
            String toolName = payload.path("toolName").asText();
            if (invocationId == null || invocationId.isBlank() || toolName == null || toolName.isBlank()) {
                return;
            }
            NodeToolCallResult callResult = new NodeToolCallResult(
                    invocationId,
                    nodeId,
                    toolName,
                    payload.path("status").asText("UNKNOWN"),
                    result,
                    textOrNull(payload, "errorMessage"));
            // status 对账可能返回 ACCEPTED/RUNNING。它们说明节点仍在处理，不是失败也不是终态。
            if (!terminalStatus(callResult.status())) {
                String argumentsDigest = textOrNull(payload, "argumentsDigest");
                int attempt = payload.path("attempt").asInt(1);
                if ("RUNNING".equalsIgnoreCase(callResult.status())) {
                    nodes.startInvocation(nodeId, callResult.invocationId(), callResult.toolName(), argumentsDigest, attempt);
                } else {
                    nodes.acceptInvocation(nodeId, callResult.invocationId(), callResult.toolName(), argumentsDigest, attempt);
                }
                return;
            }
            boolean deliveredToWaitingCall = sessions.complete(
                    envelope,
                    nodeId,
                    callResult.toolName(),
                    textOrNull(payload, "argumentsDigest"),
                    payload.path("attempt").asInt(1),
                    callResult);
            if (!deliveredToWaitingCall) {
                // 重连对账或控制面重启后没有内存 Future；仅由 NodeService 在持久记录匹配时落库。
                nodes.reconcileInvocationResult(
                        callResult,
                        callResult.toolName(),
                        textOrNull(payload, "argumentsDigest"),
                        payload.path("attempt").asInt(1));
            }
            // The originating NodeService call owns durable state transitions after its Future
            // completes. Completing it here avoids a concurrent write to the locked invocation.
            return;
        }

        if ("tool.cancel.ack".equals(type)) {
            // ACK 说明节点收到取消，不能把它误写成 CANCELLED；终态仍以 result/status 为准。
            return;
        }

        sessions.sendControl(nodeId, "node.message.ignored", envelope.messageId(),
                Map.of("reason", "Unsupported message type: " + type));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        releaseNode(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        releaseNode(session);
        session.close(CloseStatus.SERVER_ERROR);
    }

    @PreDestroy
    void beginShutdown() {
        stopping.set(true);
    }

    private void releaseNode(WebSocketSession session) {
        String nodeId = (String) session.getAttributes().get(NODE_ID_ATTR);
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        sessions.unregister(nodeId, session);
        if (!stopping.get()) {
            nodes.markOffline(nodeId);
        }
    }

    private void sendUnauthenticated(WebSocketSession session, String type, Object payload) throws Exception {
        if (session.isOpen()) {
            NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                    NodeProtocolEnvelope.CURRENT_VERSION,
                    type,
                    "msg_" + java.util.UUID.randomUUID(),
                    "session_rejected",
                    1,
                    null,
                    Instant.now(),
                    null,
                    null,
                    0,
                    objectMapper.valueToTree(payload));
            String json = objectMapper.writeValueAsString(envelope);
            if (json.getBytes(StandardCharsets.UTF_8).length > NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES) {
                throw new IllegalArgumentException("Node protocol payload exceeds the control message size limit.");
            }
            session.sendMessage(new TextMessage(json));
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private <T> List<T> listOrEmpty(JsonNode node, TypeReference<List<T>> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<T> converted = objectMapper.convertValue(node, type);
        return converted == null ? List.of() : converted;
    }

    private <T> Map<String, T> mapOrEmpty(JsonNode node, TypeReference<Map<String, T>> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        Map<String, T> converted = objectMapper.convertValue(node, type);
        return converted == null ? Map.of() : converted;
    }

    private <T> java.util.Set<T> setOrEmpty(JsonNode node, TypeReference<java.util.Set<T>> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return java.util.Set.of();
        }
        java.util.Set<T> converted = objectMapper.convertValue(node, type);
        return converted == null ? java.util.Set.of() : converted;
    }

    private Map<String, Object> toolResultMap(JsonNode resultNode) {
        if (resultNode == null || resultNode.isMissingNode() || resultNode.isNull()) {
            return Map.of();
        }
        if (resultNode.isObject()) {
            Map<String, Object> converted = objectMapper.convertValue(
                    resultNode,
                    new TypeReference<Map<String, Object>>() {
                    });
            return converted == null ? Map.of() : converted;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", objectMapper.convertValue(resultNode, Object.class));
        return Map.copyOf(wrapped);
    }

    private static boolean terminalStatus(String status) {
        return "SUCCEEDED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "TIMED_OUT".equalsIgnoreCase(status)
                || "UNKNOWN".equalsIgnoreCase(status);
    }

    static NodeHandshakeCredentials credentials(HttpHeaders headers) {
        String nodeId = headers == null ? null : headers.getFirst(NODE_ID_HEADER);
        String authorization = headers == null ? null : headers.getFirst(HttpHeaders.AUTHORIZATION);
        String nodeSecret = null;
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            nodeSecret = authorization.substring(7).trim();
        }
        return new NodeHandshakeCredentials(nodeId, nodeSecret);
    }

    record NodeHandshakeCredentials(String nodeId, String nodeSecret) {
    }
}
