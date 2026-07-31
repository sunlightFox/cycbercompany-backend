package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, String> query = parseQuery(session.getUri() == null ? "" : session.getUri().getRawQuery());
        String nodeId = query.get("nodeId");
        String nodeSecret = query.get("nodeSecret");
        try {
            NodeConnectionEntity node = nodes.authenticateNode(nodeId, nodeSecret);
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
                    "error", ex.getMessage(),
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
            List<NodeToolView> saved = nodes.saveCapabilities(nodeId, capabilities);
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
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }
}
