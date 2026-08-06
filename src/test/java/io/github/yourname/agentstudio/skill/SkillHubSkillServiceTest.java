package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SkillHubSkillServiceTest {

    @Test
    void linksResultsToThePublicSkillPageInsteadOfTheApiHost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            byte[] body = """
                    {
                      "code": 0,
                      "data": {
                        "skills": [
                          {
                            "slug": "web-tools-guide",
                            "name": "Web tools",
                            "namespace": {"handle": "community"},
                            "downloads": 42
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            SkillHubSkillService service = new SkillHubSkillService(
                    new ObjectMapper(), HttpClient.newHttpClient(), base);

            SkillHubSkillView result = service.search("web", 10).getFirst();

            assertThat(result.url())
                    .isEqualTo("https://skillhub.cn/skills/community/web-tools-guide")
                    .doesNotStartWith("https://api.skillhub.cn/");
        } finally {
            server.stop(0);
        }
    }
}
