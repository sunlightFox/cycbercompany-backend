package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class ClawHubSkillServiceTest {

    @Test
    void combinesTrendingAndQueryResultsWithoutDuplicateSkills() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/trending", exchange -> json(exchange, """
                {
                  "items": [
                    {"ownerHandle":"openai","slug":"email","displayName":"Email","summary":"Email helper","downloads":12,"official":true,
                     "links":{"canonical":"/openai/skills/email"}}
                  ]
                }
                """));
        server.createContext("/api/v1/search", exchange -> json(exchange, """
                {
                  "results": [
                    {"ownerHandle":"openai","slug":"email","displayName":"Email","summary":"Duplicate","downloads":20,
                     "links":{"canonical":"/openai/skills/email"}},
                    {"ownerHandle":"community","slug":"slides","displayName":"Slides","summary":"Slide helper","downloads":30,
                     "links":{"canonical":"/community/skills/slides"}}
                  ]
                }
                """));
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ClawHubSkillService service = new ClawHubSkillService(
                    new ObjectMapper(),
                    HttpClient.newHttpClient(),
                    base);

            var results = service.search("email", 10);

            assertThat(results).extracting(ClawHubSkillView::reference)
                    .containsExactly("community/slides", "openai/email");
            assertThat(results).first().extracting(ClawHubSkillView::downloads).isEqualTo(30L);
        } finally {
            server.stop(0);
        }
    }

    private static void json(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
