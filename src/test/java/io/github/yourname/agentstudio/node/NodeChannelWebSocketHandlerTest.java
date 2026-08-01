package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;

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
}
