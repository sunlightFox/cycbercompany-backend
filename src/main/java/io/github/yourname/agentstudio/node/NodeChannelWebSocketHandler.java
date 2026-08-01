package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
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
            session.getAttributes().put(NODE_ID_ATTR, node.id());
            sessions.register(node.id(), session);
            send(session, Map.of(
                    "type", "node.accepted",
                    "nodeId", node.id(),
                    "heartbeatIntervalSeconds", 20,
                    "timestamp", Instant.now().toString()));
        } catch (Exception ex) {
            send(session, Map.of(
                    "type", "node.rejected",
                    "error", "Node authentication failed.",
                    "timestamp", Instant.now().toString()));
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

        JsonNode root = objectMapper.readTree(message.getPayload());
        String type = root.path("type").asText("");
        if ("node.heartbeat".equals(type)) {
            nodes.heartbeat(
                    nodeId,
                    textOrNull(root, "hostname"),
                    textOrNull(root, "osName"),
                    textOrNull(root, "osArch"),
                    textOrNull(root, "clientVersion"));
            send(session, Map.of(
                    "type", "node.heartbeat.ack",
                    "timestamp", Instant.now().toString()));
            return;
        }

        if ("node.capabilities".equals(type)) {
            List<NodeCapabilityPayload> capabilities = objectMapper.convertValue(
                    root.path("capabilities"),
                    new TypeReference<List<NodeCapabilityPayload>>() {
                    });
            Map<String, String> runtimes = objectMapper.convertValue(
                    root.path("runtimes"), new TypeReference<Map<String, String>>() { });
            java.util.Set<String> features = objectMapper.convertValue(
                    root.path("features"), new TypeReference<java.util.Set<String>>() { });
            List<NodeToolView> saved = nodes.saveCapabilities(
                    nodeId,
                    textOrNull(root, "capabilityRevision"),
                    runtimes,
                    features,
                    capabilities);
            send(session, Map.of(
                    "type", "node.capabilities.ack",
                    "toolCount", saved.size(),
                    "timestamp", Instant.now().toString()));
            return;
        }

        if ("tool.result".equals(type)) {
            Map<String, Object> result = objectMapper.convertValue(
                    root.path("result"),
                    new TypeReference<Map<String, Object>>() {
                    });
            sessions.complete(new NodeToolCallResult(
                    root.path("invocationId").asText(),
                    nodeId,
                    root.path("toolName").asText(),
                    root.path("status").asText("UNKNOWN"),
                    result,
                    textOrNull(root, "errorMessage")));
            return;
        }

        send(session, Map.of(
                "type", "node.message.ignored",
                "reason", "Unsupported message type: " + type,
                "timestamp", Instant.now().toString()));
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

    private void send(WebSocketSession session, Object payload) throws Exception {
        if (session.isOpen()) {
            String json = objectMapper.writeValueAsString(payload);
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
