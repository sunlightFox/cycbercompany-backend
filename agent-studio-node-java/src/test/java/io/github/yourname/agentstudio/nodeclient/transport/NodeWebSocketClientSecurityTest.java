package io.github.yourname.agentstudio.nodeclient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.NodeConfig;
import io.github.yourname.agentstudio.nodeclient.NodeAccessMode;
import io.github.yourname.agentstudio.nodeclient.SystemInfo;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class NodeWebSocketClientSecurityTest {

    @Test
    void removesLegacySecretQueryBeforeConnectingOrLogging() {
        NodeConfig config = new NodeConfig(
                "http://localhost:8080",
                "node-1",
                "ns_do_not_log_me",
                "/api/v1/node-channel?nodeId=node-1&nodeSecret=ns_do_not_log_me",
                "test-node",
                null,
                null);
        NodeWebSocketClient client = new NodeWebSocketClient(
                new ObjectMapper(), HttpClient.newHttpClient(), config, SystemInfo.current());

        URI uri = client.websocketUri();

        assertEquals("ws://localhost:8080/api/v1/node-channel", uri.toString());
        assertEquals("ws://localhost:8080", NodeWebSocketClient.safeServerAddress(uri));
        assertFalse(uri.toString().contains(config.nodeSecret()));
    }

    @Test
    void shutdownControlClosesTheWebSocket() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-test");
        NodeConfig config = new NodeConfig(
                "http://localhost:8080",
                "node-1",
                "ns_secret",
                "/api/v1/node-channel",
                "test-node",
                workspace.toString(),
                NodeAccessMode.SYSTEM.name());
        NodeWebSocketClient client = new NodeWebSocketClient(
                new ObjectMapper().findAndRegisterModules(), HttpClient.newHttpClient(), config, SystemInfo.current());
        FakeWebSocket socket = new FakeWebSocket();
        List<Boolean> connectionEvents = new ArrayList<>();
        client.setConnectionObserver(connectionEvents::add);

        client.onOpen(socket);
        client.onText(socket, new ObjectMapper().findAndRegisterModules().writeValueAsString(
                new io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolEnvelope(
                        "1.1", "node.accepted", "msg-1", "session-1", 1, null,
                        java.time.Instant.now(), null, null, 1, new ObjectMapper().valueToTree(
                                java.util.Map.of("heartbeatIntervalSeconds", 1)))), true);
        client.onText(socket, new ObjectMapper().findAndRegisterModules().writeValueAsString(
                new io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolEnvelope(
                        "1.1", "node.shutdown", "msg-2", "session-1", 2, null,
                                java.time.Instant.now(), null, null, 1, new ObjectMapper().valueToTree(
                                java.util.Map.of("reason", "stop now")))), true);

        assertEquals(WebSocket.NORMAL_CLOSURE, socket.closeStatus);
        assertEquals("stop now", socket.closeReason);
        assertEquals(List.of(true, false), connectionEvents);
    }

    @Test
    void acceptedControlWithNullPayloadStillInitializesTheClient() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-test");
        NodeConfig config = new NodeConfig(
                "http://localhost:8080",
                "node-1",
                "ns_secret",
                "/api/v1/node-channel",
                "test-node",
                workspace.toString(),
                NodeAccessMode.SYSTEM.name());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeWebSocketClient client = new NodeWebSocketClient(
                mapper, HttpClient.newHttpClient(), config, SystemInfo.current());
        FakeWebSocket socket = new FakeWebSocket();

        client.onOpen(socket);
        client.onText(socket, mapper.writeValueAsString(
                new io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolEnvelope(
                        "1.1", "node.accepted", "msg-1", "session-1", 1, null,
                        java.time.Instant.now(), null, null, 1, null)), true);
        client.onText(socket, mapper.writeValueAsString(
                new io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolEnvelope(
                        "1.1", "node.shutdown", "msg-2", "session-1", 2, null,
                        java.time.Instant.now(), null, null, 1, null)), true);

        assertFalse(socket.sentTexts.isEmpty());
        assertEquals(WebSocket.NORMAL_CLOSURE, socket.closeStatus);
        assertEquals("server requested shutdown", socket.closeReason);
    }

    @Test
    void malformedToolInvokeWithInvocationIdReturnsFailedResult() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-test");
        NodeConfig config = new NodeConfig(
                "http://localhost:8080",
                "node-1",
                "ns_secret",
                "/api/v1/node-channel",
                "test-node",
                workspace.toString(),
                NodeAccessMode.SYSTEM.name());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeWebSocketClient client = new NodeWebSocketClient(
                mapper, HttpClient.newHttpClient(), config, SystemInfo.current());
        FakeWebSocket socket = new FakeWebSocket();

        client.onOpen(socket);
        client.onText(socket, mapper.writeValueAsString(
                new io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolEnvelope(
                        "1.1", "node.accepted", "msg-1", "session-1", 1, null,
                        java.time.Instant.now(), null, null, 1, mapper.valueToTree(
                        java.util.Map.of("heartbeatIntervalSeconds", 20)))), true);
        client.onText(socket, mapper.writeValueAsString(
                new io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolEnvelope(
                        "1.1", "tool.invoke", "msg-2", "session-1", 2, "inv-1",
                        java.time.Instant.now(), null, "trace-1", 1, mapper.valueToTree(
                        java.util.Map.of("invocationId", "inv-1", "argumentsDigest", "sha256:args")))), true);

        JsonNode result = socket.sentTexts.stream()
                .map(text -> read(mapper, text))
                .filter(envelope -> "tool.result".equals(envelope.path("type").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals("inv-1", result.path("payload").path("invocationId").asText());
        assertEquals("FAILED", result.path("payload").path("status").asText());
        assertEquals(
                "Malformed tool.invoke: toolName is required.",
                result.path("payload").path("errorMessage").asText());
    }

    @Test
    void closesTheConnectionForMalformedOrEmptyServerEnvelopes() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-test");
        NodeConfig config = new NodeConfig(
                "http://localhost:8080",
                "node-1",
                "ns_secret",
                "/api/v1/node-channel",
                "test-node",
                workspace.toString(),
                NodeAccessMode.SYSTEM.name());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NodeWebSocketClient client = new NodeWebSocketClient(
                mapper, HttpClient.newHttpClient(), config, SystemInfo.current());
        FakeWebSocket socket = new FakeWebSocket();

        client.onOpen(socket);
        client.onText(socket, "{not-json", true);

        assertEquals(1007, socket.closeStatus);
        assertEquals("invalid protocol JSON", socket.closeReason);

        FakeWebSocket emptyEnvelopeSocket = new FakeWebSocket();
        client.onOpen(emptyEnvelopeSocket);
        client.onText(emptyEnvelopeSocket, "null", true);

        assertEquals(1007, emptyEnvelopeSocket.closeStatus);
        assertEquals("invalid protocol envelope", emptyEnvelopeSocket.closeReason);
    }

    private static JsonNode read(ObjectMapper mapper, String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        int closeStatus = -1;
        String closeReason;
        final List<String> sentTexts = new ArrayList<>();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentTexts.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            this.closeStatus = statusCode;
            this.closeReason = reason;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
