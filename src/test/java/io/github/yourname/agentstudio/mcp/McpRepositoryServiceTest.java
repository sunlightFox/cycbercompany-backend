package io.github.yourname.agentstudio.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class McpRepositoryServiceTest {

    @Test
    void returnsRepositoryFallbackWhenMcpMarketIsUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/servers", exchange -> {
            byte[] body = "temporarily unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            McpRepositoryService service = new McpRepositoryService(
                    new ObjectMapper(), HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            assertThat(service.curated())
                    .isNotEmpty()
                    .extracting(McpRepositoryView::name)
                    .contains("modelcontextprotocol/servers");
        } finally {
            server.stop(0);
        }
    }
}
