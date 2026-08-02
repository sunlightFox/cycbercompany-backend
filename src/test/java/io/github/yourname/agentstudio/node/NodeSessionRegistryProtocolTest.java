package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class NodeSessionRegistryProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void rejectsInboundResultFromSupersededFencingToken() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        NodeSessionRegistry registry = new NodeSessionRegistry(objectMapper);
        registry.register("node-1", socket, "session-current", 9);

        NodeProtocolEnvelope stale = new NodeProtocolEnvelope(
                "1.1", "tool.result", "msg-1", "session-old", 1, "nodeinv-1",
                Instant.now(), null, null, 8, objectMapper.valueToTree(Map.of()));

        assertThat(registry.acceptInbound("node-1", socket, stale)).isFalse();
    }

    @Test
    void controlMessageUsesEnvelopeWithCurrentSessionAndSequence() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        NodeSessionRegistry registry = new NodeSessionRegistry(objectMapper);
        registry.register("node-1", socket, "session-current", 9);

        registry.sendControl("node-1", "node.heartbeat.ack", "msg-request", Map.of());

        org.mockito.ArgumentCaptor<TextMessage> captured = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        org.mockito.Mockito.verify(socket).sendMessage(captured.capture());
        NodeProtocolEnvelope envelope = objectMapper.readValue(captured.getValue().getPayload(), NodeProtocolEnvelope.class);
        assertThat(envelope.protocolVersion()).isEqualTo("1.1");
        assertThat(envelope.sessionId()).isEqualTo("session-current");
        assertThat(envelope.fencingToken()).isEqualTo(9);
        assertThat(envelope.sequence()).isPositive();
        assertThat(envelope.correlationId()).isEqualTo("msg-request");
    }
}
