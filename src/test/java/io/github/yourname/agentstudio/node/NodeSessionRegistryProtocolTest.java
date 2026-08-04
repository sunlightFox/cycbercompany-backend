package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    @Test
    void disconnectAfterDispatchReturnsUnknownWithoutReplayingTheSideEffect() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        NodeSessionRegistry registry = new NodeSessionRegistry(objectMapper);
        registry.register("node-1", socket, "session-current", 9);
        NodeInvocationDispatch dispatch = new NodeInvocationDispatch(
                "inv-write-1", "run-1", "call-1", "fs.write",
                Map.of("path", "note.txt", "content", "one write"),
                "fixture", null, Instant.now().plusSeconds(30), "policy-1", "sha256:args", 1,
                "idem-1", "trace-1");

        CompletableFuture<NodeToolCallResult> resultFuture = CompletableFuture.supplyAsync(
                () -> registry.invoke("node-1", dispatch, Duration.ofSeconds(5)));
        org.mockito.Mockito.verify(socket, org.mockito.Mockito.timeout(1000))
                .sendMessage(any(org.springframework.web.socket.TextMessage.class));

        // 断线只结束当前等待；恢复流程只能查询 journal，不能再次调用 invoke。
        registry.unregister("node-1", socket);
        NodeToolCallResult result = resultFuture.get(2, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        verify(socket, org.mockito.Mockito.times(1)).sendMessage(any(org.springframework.web.socket.TextMessage.class));
    }

    @Test
    void directToolCallUsesTheCurrentEnvelopeProtocol() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        NodeSessionRegistry registry = new NodeSessionRegistry(objectMapper);
        registry.register("node-1", socket, "session-current", 9);

        CompletableFuture<NodeToolCallResult> resultFuture = CompletableFuture.supplyAsync(
                () -> registry.invoke("node-1", "system.fs.delete", Map.of("path", "C:/fixture/remove-me"),
                        Duration.ofSeconds(5)));
        org.mockito.ArgumentCaptor<TextMessage> captured = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        org.mockito.Mockito.verify(socket, org.mockito.Mockito.timeout(1000)).sendMessage(captured.capture());

        NodeProtocolEnvelope envelope = objectMapper.readValue(captured.getValue().getPayload(), NodeProtocolEnvelope.class);
        assertThat(envelope.protocolVersion()).isEqualTo(NodeProtocolEnvelope.CURRENT_VERSION);
        assertThat(envelope.type()).isEqualTo("tool.invoke");
        assertThat(envelope.payload().path("toolName").asText()).isEqualTo("system.fs.delete");
        assertThat(envelope.payload().path("argumentsDigest").asText()).isEqualTo("legacy");
        assertThat(envelope.payload().path("attempt").asInt()).isEqualTo(1);

        registry.unregister("node-1", socket);
        assertThat(resultFuture.get(2, TimeUnit.SECONDS).status()).isEqualTo("UNKNOWN");
    }
}
