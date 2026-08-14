package io.github.yourname.cycbercompany.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SkillRepositoryServiceTest {

    @Test
    void curatedSourcesCoverABroaderSkillMarketplaceSurface() {
        SkillRepositoryService service = new SkillRepositoryService(new ObjectMapper());

        assertThat(service.curated())
                .hasSizeGreaterThanOrEqualTo(10)
                .extracting(SkillRepositoryView::name)
                .contains(
                        "OpenAI/skills",
                        "anthropics/skills",
                        "microsoft/skills",
                        "supabase/agent-skills",
                        "VoltAgent/awesome-agent-skills");
    }

    @Test
    void searchFallsBackToCuratedRepositoriesWhenGitHubIsUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/repositories", exchange -> {
            byte[] body = "rate limited".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            SkillRepositoryService service = new SkillRepositoryService(
                    HttpClient.newHttpClient(), new ObjectMapper(), base, base);

            assertThat(service.search(new SearchSkillRepositoriesCommand("", 3)))
                    .hasSize(3)
                    .extracting(SkillRepositoryView::name)
                    .contains("OpenAI/skills");
        } finally {
            server.stop(0);
        }
    }
}
