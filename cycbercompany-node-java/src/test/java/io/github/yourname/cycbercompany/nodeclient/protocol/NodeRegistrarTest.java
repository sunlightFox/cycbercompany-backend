package io.github.yourname.cycbercompany.nodeclient.protocol;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yourname.cycbercompany.nodeclient.SystemInfo;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NodeRegistrarTest {

    @Test
    void acceptsLoopbackHttpAndHttpsUrls() {
        assertThatCode(() -> NodeRegistrar.requireLoopbackServer("http://127.0.0.1:8080"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NodeRegistrar.requireLoopbackServer("https://localhost:8443/"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NodeRegistrar.requireLoopbackServer("http://[::1]:8080"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRemoteOrInvalidBootstrapUrlsBeforeSendingARequest() {
        assertThatThrownBy(() -> NodeRegistrar.requireLoopbackServer("http://192.168.1.20:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> NodeRegistrar.requireLoopbackServer("ws://127.0.0.1:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback HTTP(S)");
        assertThatThrownBy(() -> NodeRegistrar.requireLoopbackServer(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback server URL");
    }

    @Test
    void bootstrapDoesNotSendAnAuthorizationHeader() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v1/local-executor/bootstrap", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, """
                        {"nodeId":"node-1","nodeSecret":"secret-1","websocketUrl":"ws://127.0.0.1/node-channel"}
                        """);
            });
            server.start();

            NodeRegistrar registrar = new NodeRegistrar(new ObjectMapper(), HttpClient.newHttpClient());
            var config = registrar.bootstrapLocalExecutor(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "This computer",
                    SystemInfo.current());

            org.assertj.core.api.Assertions.assertThat(authorization).hasValue(null);
            org.assertj.core.api.Assertions.assertThat(config.nodeSecret()).isEqualTo("secret-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
