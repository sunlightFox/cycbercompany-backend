package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class NodeChannelWebSocketHandlerTest {

    @Test
    void readsNodeCredentialsOnlyFromHandshakeHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(NodeChannelWebSocketHandler.NODE_ID_HEADER, "node-123");
        headers.setBearerAuth("ns_super_secret");

        NodeChannelWebSocketHandler.NodeHandshakeCredentials credentials =
                NodeChannelWebSocketHandler.credentials(headers);

        assertThat(credentials.nodeId()).isEqualTo("node-123");
        assertThat(credentials.nodeSecret()).isEqualTo("ns_super_secret");
    }

    @Test
    void missingOrNonBearerAuthorizationDoesNotProduceASecret() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(NodeChannelWebSocketHandler.NODE_ID_HEADER, "node-123");
        headers.set(HttpHeaders.AUTHORIZATION, "Basic unsafe");

        assertThat(NodeChannelWebSocketHandler.credentials(headers).nodeSecret()).isNull();
        assertThat(NodeChannelWebSocketHandler.credentials(new HttpHeaders()).nodeId()).isNull();
    }

    @Test
    void rejectsOversizedUtf8MessageBeforeJsonParsing() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);

        NodeChannelWebSocketHandler handler =
                new NodeChannelWebSocketHandler(nodes, sessions, new ObjectMapper());
        String payload = "你".repeat(NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES / 3 + 1);

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(session).close(CloseStatus.TOO_BIG_TO_PROCESS);
    }

    @Test
    void closesMalformedNodeProtocolJsonWithoutThrowing() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);

        NodeChannelWebSocketHandler handler =
                new NodeChannelWebSocketHandler(nodes, sessions, new ObjectMapper());

        handler.handleTextMessage(session, new TextMessage("{not-json"));

        org.mockito.ArgumentCaptor<CloseStatus> status = org.mockito.ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(1007);
    }

    @Test
    void treatsNullHeartbeatPayloadAsEmptyPayload() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessions.acceptInbound(eq("node-123"), eq(session), any())).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                "1.1", "node.heartbeat", "msg-heartbeat", "session-1", 1, null,
                Instant.now(), null, null, 1, null);
        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, mapper);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(envelope)));

        verify(nodes).heartbeat("node-123", null, null, null, null);
        verify(sessions).sendControl("node-123", "node.heartbeat.ack", "msg-heartbeat", java.util.Map.of());
    }

    @Test
    void treatsNullCapabilitiesPayloadAsEmptyPayload() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessions.acceptInbound(eq("node-123"), eq(session), any())).thenReturn(true);
        when(nodes.saveCapabilities(eq("node-123"), eq(null), eq(java.util.Map.of()), eq(java.util.Set.of()), eq(List.of())))
                .thenReturn(List.of());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                "1.1", "node.capabilities", "msg-capabilities", "session-1", 1, null,
                Instant.now(), null, null, 1, null);
        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, mapper);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(envelope)));

        verify(nodes).saveCapabilities("node-123", null, java.util.Map.of(), java.util.Set.of(), List.of());
        verify(sessions).sendControl(
                "node-123", "node.capabilities.ack", "msg-capabilities", java.util.Map.of("toolCount", 0));
    }

    @Test
    void requestsJournalStatusAfterAcceptingAReconnectedNodeWithoutReplayingInvocation() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        when(session.getAttributes()).thenReturn(attributes);
        when(nodes.authenticateNode("node-123", "secret")).thenReturn(new NodeConnectionEntity(
                "node-123", "tenant-a", "local", "host", "Windows", "amd64", "test", "hash", Instant.now()));
        when(nodes.reconciliationRequests("node-123", 100)).thenReturn(List.of(
                new NodeService.NodeInvocationReconciliation("inv-1", "fs.write", "sha256:args", 2)));

        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, new ObjectMapper());
        HttpHeaders headers = new HttpHeaders();
        headers.set(NodeChannelWebSocketHandler.NODE_ID_HEADER, "node-123");
        headers.setBearerAuth("secret");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        handler.afterConnectionEstablished(session);

        verify(sessions).sendControl("node-123", "node.accepted", null, java.util.Map.of(
                "nodeId", "node-123", "heartbeatIntervalSeconds", 20, "fencingToken", 0L));
        verify(sessions).sendControl("node-123", "tool.status", "inv-1", java.util.Map.of(
                "invocationId", "inv-1", "toolName", "fs.write", "argumentsDigest", "sha256:args", "attempt", 2));
        verify(sessions, times(0)).sendControl("node-123", "tool.invoke", "inv-1", java.util.Map.of());
    }

    @Test
    void persistsAReconciledTerminalStatusWhenNoInMemoryCallIsWaiting() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessions.acceptInbound(eq("node-123"), eq(session), any())).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                "1.1", "tool.status.result", "msg-1", "session-1", 1, "inv-1", Instant.now(), null, null, 1,
                mapper.valueToTree(java.util.Map.of(
                        "invocationId", "inv-1", "toolName", "fs.write", "argumentsDigest", "sha256:args",
                        "attempt", 2, "status", "SUCCEEDED", "result", java.util.Map.of("written", true))));
        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, mapper);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(envelope)));

        verify(nodes).reconcileInvocationResult(any(NodeToolCallResult.class), eq("fs.write"), eq("sha256:args"), eq(2));
    }

    @Test
    void ignoresToolResultsWithoutRequiredCorrelationFields() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessions.acceptInbound(eq("node-123"), eq(session), any())).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                "1.1", "tool.result", "msg-1", "session-1", 1, "inv-1", Instant.now(), null, null, 1,
                mapper.valueToTree(java.util.Map.of(
                        "invocationId", "inv-1", "argumentsDigest", "sha256:args", "attempt", 1,
                        "status", "SUCCEEDED", "result", java.util.Map.of("ok", true))));
        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, mapper);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(envelope)));

        verify(sessions, never()).complete(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(nodes, never()).reconcileInvocationResult(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void normalizesNullToolResultToAnEmptyMap() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessions.acceptInbound(eq("node-123"), eq(session), any())).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var payload = mapper.createObjectNode();
        payload.put("invocationId", "inv-1");
        payload.put("toolName", "system.shell.run");
        payload.put("argumentsDigest", "sha256:args");
        payload.put("attempt", 1);
        payload.put("status", "FAILED");
        payload.putNull("result");
        payload.put("errorMessage", "NullPointerException");
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                "1.1", "tool.result", "msg-1", "session-1", 1, "inv-1", Instant.now(), null, null, 1, payload);
        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, mapper);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(envelope)));

        org.mockito.ArgumentCaptor<NodeToolCallResult> result =
                org.mockito.ArgumentCaptor.forClass(NodeToolCallResult.class);
        verify(nodes).reconcileInvocationResult(result.capture(), eq("system.shell.run"), eq("sha256:args"), eq(1));
        assertThat(result.getValue().result()).isEmpty();
        assertThat(result.getValue().errorMessage()).isEqualTo("NullPointerException");
    }

    @Test
    void preservesNullFieldsInsideObjectToolResults() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessions.acceptInbound(eq("node-123"), eq(session), any())).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var resultNode = mapper.createObjectNode();
        resultNode.put("stdout", "ok");
        resultNode.putNull("stderr");
        var payload = mapper.createObjectNode();
        payload.put("invocationId", "inv-1");
        payload.put("toolName", "system.shell.run");
        payload.put("argumentsDigest", "sha256:args");
        payload.put("attempt", 1);
        payload.put("status", "SUCCEEDED");
        payload.set("result", resultNode);
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                "1.1", "tool.result", "msg-1", "session-1", 1, "inv-1", Instant.now(), null, null, 1, payload);
        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, mapper);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(envelope)));

        org.mockito.ArgumentCaptor<NodeToolCallResult> result =
                org.mockito.ArgumentCaptor.forClass(NodeToolCallResult.class);
        verify(nodes).reconcileInvocationResult(result.capture(), eq("system.shell.run"), eq("sha256:args"), eq(1));
        assertThat(result.getValue().result()).containsEntry("stdout", "ok");
        assertThat(result.getValue().result()).containsKey("stderr");
        assertThat(result.getValue().result().get("stderr")).isNull();
    }

    @Test
    void bindsIntermediateStatusToThePersistedInvocationMetadata() throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("nodeId", "node-123");
        when(session.getAttributes()).thenReturn(attributes);
        when(sessions.acceptInbound(eq("node-123"), eq(session), any())).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeProtocolEnvelope envelope = new NodeProtocolEnvelope(
                "1.1", "tool.status.result", "msg-progress", "session-1", 1, "inv-1", Instant.now(), null, null, 1,
                mapper.valueToTree(java.util.Map.of(
                        "invocationId", "inv-1", "toolName", "fs.write", "argumentsDigest", "wrong-digest",
                        "attempt", 2, "status", "RUNNING")));
        NodeChannelWebSocketHandler handler = new NodeChannelWebSocketHandler(nodes, sessions, mapper);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(envelope)));

        verify(nodes).startInvocation("node-123", "inv-1", "fs.write", "wrong-digest", 2);
    }
}
