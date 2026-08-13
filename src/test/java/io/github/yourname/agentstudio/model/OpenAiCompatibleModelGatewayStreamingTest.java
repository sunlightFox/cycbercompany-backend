package io.github.yourname.agentstudio.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleModelGatewayStreamingTest {

    @Test
    void stopsAtDoneMarkerWithoutWaitingForTheProviderToCloseItsConnection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                String event = new ObjectMapper().writeValueAsString(Map.of(
                        "model", "mock",
                        "choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(Map.of(
                                "index", 0,
                                "id", "call-1",
                                "type", "function",
                                "function", Map.of("name", "read", "arguments", "{\"path\":\"README\"}"))))))));
                output.write(("data: " + event + "\n\n"
                        + "data: [DONE]\n\n").getBytes());
                output.flush();
                // A keep-alive provider may leave the HTTP response open for reuse.
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            ModelProfileRepository profiles = Mockito.mock(ModelProfileRepository.class);
            ModelProfileEntity profile = new ModelProfileEntity(
                    "mock", ProviderType.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "mock", null, "test-key", Set.of(ModelCapability.TEXT, ModelCapability.TOOLS), true, Instant.now());
            when(profiles.findById("mock")).thenReturn(Optional.of(profile));
            OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                    profiles, RestClient.builder(), new ObjectMapper());

            long started = System.nanoTime();
            ModelGateway.ModelAnswer answer = gateway.stream(new ModelGateway.ModelCompletionRequest(
                    "mock", List.of(new ModelGateway.ModelMessage("user", "Read README")),
                    List.of(new ModelGateway.ModelTool("read", "Read a file", java.util.Map.of())),
                    ModelGateway.ToolChoice.REQUIRED), ignored -> { });
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
            assertThat(answer.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.name()).isEqualTo("read");
                assertThat(call.arguments()).containsEntry("path", "README");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesAProviderSocketResetAsRetryableTransportFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    // Close without returning an HTTP response to emulate a provider reset.
                } catch (Exception ignored) {
                    // The test server is deliberately short-lived.
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            ModelProfileRepository profiles = Mockito.mock(ModelProfileRepository.class);
            ModelProfileEntity profile = new ModelProfileEntity(
                    "mock", ProviderType.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:" + server.getLocalPort() + "/v1",
                    "mock", null, "test-key", Set.of(ModelCapability.TEXT), true, Instant.now());
            when(profiles.findById("mock")).thenReturn(Optional.of(profile));
            OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                    profiles, RestClient.builder(), new ObjectMapper());

            assertThatThrownBy(() -> gateway.stream(new ModelGateway.ModelCompletionRequest(
                    "mock", List.of(new ModelGateway.ModelMessage("user", "Continue")),
                    List.of(), ModelGateway.ToolChoice.AUTO), ignored -> { }))
                    .isInstanceOf(ModelTransientException.class)
                    .hasMessageContaining("stream transport failed");
        }
    }

    @Test
    void classifiesAnEofWithoutACompletionSignalAsRetryable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes());
            }
        });
        server.start();
        try {
            ModelProfileRepository profiles = Mockito.mock(ModelProfileRepository.class);
            ModelProfileEntity profile = new ModelProfileEntity(
                    "mock", ProviderType.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "mock", null, "test-key", Set.of(ModelCapability.TEXT), true, Instant.now());
            when(profiles.findById("mock")).thenReturn(Optional.of(profile));
            OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                    profiles, RestClient.builder(), new ObjectMapper());

            assertThatThrownBy(() -> gateway.stream(new ModelGateway.ModelCompletionRequest(
                    "mock", List.of(new ModelGateway.ModelMessage("user", "Continue")),
                    List.of(), ModelGateway.ToolChoice.AUTO), ignored -> { }))
                    .isInstanceOf(ModelTransientException.class)
                    .hasMessageContaining("completion signal");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsFinishReasonAsTheCompletionSignalWithoutADoneMarker() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"complete\"},\"finish_reason\":\"stop\"}]}\n\n".getBytes());
                output.flush();
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            ModelProfileRepository profiles = Mockito.mock(ModelProfileRepository.class);
            ModelProfileEntity profile = new ModelProfileEntity(
                    "mock", ProviderType.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "mock", null, "test-key", Set.of(ModelCapability.TEXT), true, Instant.now());
            when(profiles.findById("mock")).thenReturn(Optional.of(profile));
            OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                    profiles, RestClient.builder(), new ObjectMapper());

            long started = System.nanoTime();
            ModelGateway.ModelAnswer answer = gateway.stream(new ModelGateway.ModelCompletionRequest(
                    "mock", List.of(new ModelGateway.ModelMessage("user", "Continue")),
                    List.of(), ModelGateway.ToolChoice.AUTO), ignored -> { });

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(answer.content()).isEqualTo("complete");
            assertThat(answer.finishReason()).isEqualTo("stop");
        } finally {
            server.stop(0);
        }
    }
}
