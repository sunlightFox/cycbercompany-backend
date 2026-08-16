package io.github.yourname.cycbercompany.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.yourname.cycbercompany.config.AppProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebSearchServiceTest {

    @Test
    void usesConfiguredTavilyApiAndReadsVerifiedEvidence() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            respondJson(exchange, """
                    {"results":[{"title":"Spring Framework reference","url":"http://localhost:%s/spring","content":"Spring Framework reference","published_date":"%s"}]}
                    """.formatted(server.getAddress().getPort(), Instant.now()));
        });
        server.createContext("/spring", exchange -> respondHtml(exchange,
                "<main>Spring Framework reference documents the current Java API.</main>"));
        server.start();
        try {
            WebSearchResponse response = new WebSearchService(properties(server, true)).searchDetailed(new WebSearchCommand(
                    "Spring current", 5, WebSearchMode.TECHNICAL, WebSearchFreshness.WEEK, List.of(), List.of(), true));

            assertThat(requests).hasValue(1);
            assertThat(response.trace().providers()).singleElement()
                    .extracting(WebSearchProviderTrace::sourceId, WebSearchProviderTrace::status)
                    .containsExactly("tavily/general", "SUCCESS");
            assertThat(response.results()).singleElement()
                    .extracting(WebSearchResult::sourceType).isEqualTo("TAVILY");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsOnlyTavilyWhenTheApiKeyIsMissing() {
        AppProperties properties = new AppProperties(null, null, null,
                new AppProperties.WebSearch(true, 5, "https://api.tavily.com/search", "", 2, 3,
                        AppProperties.PageReader.defaults(), AppProperties.SearchPlanning.defaults()),
                null, null, null);

        WebSearchResponse response = new WebSearchService(properties).searchDetailed(new WebSearchCommand("today technology news", 5));

        assertThat(response.trace().providers()).allSatisfy(trace -> {
            assertThat(trace.sourceId()).startsWith("tavily/");
            assertThat(trace.detail()).contains("API key is not configured");
        });
    }

    @Test
    void blocksPrivateAndReservedAddressesBeyondJavaSiteLocalChecks() throws Exception {
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
    }

    @Test
    void parsesRelativeIndexTimesAndCalendarDatesFromNewsUrls() {
        Instant now = Instant.parse("2026-07-31T12:00:00Z");
        assertThat(WebSearchService.parseRelativePublicationDate("7 hours ago", now)).isEqualTo(now.minus(Duration.ofHours(7)));
        assertThat(WebSearchService.parsePublicationDateFromUrl("https://example.com/news/2026/07/31/story.html"))
                .isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
    }

    @Test
    void ranksSourceRepositoryAheadOfSecondaryCoverageForPrimarySourceQuestion() {
        WebSearchResult repost = new WebSearchResult("DeepSeek Harness overview", "https://news.example/deepseek-harness",
                "A secondary report about DeepSeek Harness.");
        WebSearchResult repository = new WebSearchResult("deepseek-ai/deepseek-harness",
                "https://github.com/deepseek-ai/deepseek-harness", "Official project source code.");

        WebSearchService.RankingResult ranked = WebSearchService.rankCandidates(
                List.of(repost, repository), "DeepSeek Harness official GitHub repository", 5, 2, 1,
                List.of(), List.of(), WebSearchFreshness.ANY);

        assertThat(ranked.results()).extracting(WebSearchResult::url)
                .startsWith("https://github.com/deepseek-ai/deepseek-harness");
    }

    private static AppProperties properties(HttpServer server, boolean allowPrivateHosts) {
        return new AppProperties(null, null, null,
                new AppProperties.WebSearch(true, 5, "http://localhost:" + server.getAddress().getPort(), "test-key", 2, 3,
                        new AppProperties.PageReader(true, 3, 1_000, 100_000, allowPrivateHosts),
                        new AppProperties.SearchPlanning(1, 90)),
                null, null, null);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        respond(exchange, body, "application/json; charset=utf-8");
    }

    private static void respondHtml(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        respond(exchange, body, "text/html; charset=utf-8");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
