package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
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

        NodeProtocolEnvelope envelope = objectMapper.readValue(message.getPayload(), NodeProtocolEnvelope.class);
        if (!sessions.acceptInbound(nodeId, session, envelope)) {
            // 旧 fencing token、过期帧、错误 session 或乱序帧都不能影响最新连接。
            return;
        }
        String type = envelope.type();
        JsonNode payload = envelope.payload();
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
            List<NodeCapabilityPayload> capabilities = objectMapper.convertValue(
                    payload.path("capabilities"),
                    new TypeReference<List<NodeCapabilityPayload>>() {
                    });
            Map<String, String> runtimes = objectMapper.convertValue(
                    payload.path("runtimes"), new TypeReference<Map<String, String>>() { });
            java.util.Set<String> features = objectMapper.convertValue(
                    payload.path("features"), new TypeReference<java.util.Set<String>>() { });
            List<NodeToolView> saved = nodes.saveCapabilities(
                    nodeId,
                    textOrNull(payload, "capabilityRevision"),
                    runtimes,
                    features,
                    capabilities);
            sessions.sendControl(nodeId, "node.capabilities.ack", envelope.messageId(), Map.of("toolCount", saved.size()));
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
            Map<String, Object> result = objectMapper.convertValue(
                    payload.path("result"),
                    new TypeReference<Map<String, Object>>() {
                    });
            NodeToolCallResult callResult = new NodeToolCallResult(
                    payload.path("invocationId").asText(),
                    nodeId,
                    payload.path("toolName").asText(),
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
        String nodeId = (String) session.getAttributes().get(NODE_ID_ATTR);
        if (nodeId != null && !nodeId.isBlank()) {
            sessions.unregister(nodeId, session);
            nodes.markOffline(nodeId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String nodeId = (String) session.getAttributes().get(NODE_ID_ATTR);
        if (nodeId != null && !nodeId.isBlank()) {
            sessions.unregister(nodeId, session);
            nodes.markOffline(nodeId);
        }
        session.close(CloseStatus.SERVER_ERROR);
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
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
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
