package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.yourname.agentstudio.config.AppProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebSearchServiceTest {

    @Test
    void honorsContainerSearxngEndpointWhenOnlyTheLocalDefaultWasConfigured() {
        AppProperties.WebSearch configured = new AppProperties.WebSearch(true, 5, "http://localhost:8888");

        assertThat(WebSearchService.effectiveConfig(configured, "http://searxng:8080").endpoint())
                .isEqualTo("http://searxng:8080");
        assertThat(WebSearchService.effectiveConfig(configured, "").endpoint())
                .isEqualTo("http://localhost:8888");
    }

    @Test
    void blocksPrivateAndReservedAddressesBeyondJavaSiteLocalChecks() throws Exception {
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("100.64.0.1"))).isFalse();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("::ffff:127.0.0.1"))).isFalse();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(WebSearchService.isPubliclyRoutableAddress(InetAddress.getByName("fe80::1"))).isFalse();
    }

    @Test
    void usesSearxngJsonPreservesQueryAndReadsVerifiedEvidence() throws Exception {
        AtomicReference<String> requestedQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            requestedQuery.set(exchange.getRequestURI().getRawQuery());
            respondJson(exchange, """
                    {"results":[
                      {"title":"Spring Framework reference","url":"http://localhost:%s/spring","content":"Official Spring documentation","engines":["bing"],"publishedDate":"%s"},
                      {"title":"Java language guide","url":"http://localhost:%s/java","content":"Java reference material","engines":["mojeek"]}
                    ]}
                    """.formatted(server.getAddress().getPort(), Instant.now(), server.getAddress().getPort()));
        });
        server.createContext("/spring", exchange -> respondHtml(exchange,
                "<html><title>Spring Framework reference</title><main>Spring Framework reference documents the current Java API.</main></html>"));
        server.createContext("/java", exchange -> respondHtml(exchange,
                "<html><title>Java guide</title><article>Java API guidance and migration notes.</article></html>"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/spring");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            WebSearchService service = new WebSearchService(properties(server, true));

            WebSearchResponse response = service.searchDetailed(new WebSearchCommand(
                    "Spring current", 5, WebSearchMode.TECHNICAL, WebSearchFreshness.WEEK, List.of(), List.of(), true));

            assertThat(requestedQuery.get()).contains("q=Spring+current").contains("categories=general")
                    .contains("time_range=month").contains("format=json");
            assertThat(response.trace().providers()).singleElement()
                    .extracting(WebSearchProviderTrace::sourceId, WebSearchProviderTrace::status)
                    .containsExactly("searxng/general", "SUCCESS");
            assertThat(response.results()).hasSize(2);
            assertThat(response.results().getFirst().evidence())
                    .extracting(WebEvidence::readable, WebEvidence::relevant)
                    .containsExactly(true, true);
            assertThat(response.trace().pagesRead()).isEqualTo(2);
            assertThat(response.trace().verifiedPages()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void neverFallsBackToRssWhenSearxngReturnsNoResults() throws Exception {
        AtomicInteger nonSearchRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, "{\"results\":[]}"));
        server.createContext("/feed", exchange -> {
            nonSearchRequests.incrementAndGet();
            respondJson(exchange, "{\"unexpected\":true}");
        });
        server.start();
        try {
            WebSearchService service = new WebSearchService(properties(server, true));
            WebSearchResponse response = service.searchDetailed(new WebSearchCommand(
                    "today technology news", 5, WebSearchMode.NEWS, WebSearchFreshness.DAY, List.of(), List.of(), true));

            assertThat(response.results()).isEmpty();
            assertThat(response.trace().providers()).extracting(WebSearchProviderTrace::sourceId)
                    .containsExactly("searxng/news", "searxng/general-fallback");
            assertThat(nonSearchRequests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultsNewsToTodayAndRejectsCandidatesWithoutCurrentPublicationDates() throws Exception {
        AtomicReference<String> requestedQuery = new AtomicReference<>();
        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            requestedQuery.set(exchange.getRequestURI().getRawQuery());
            respondJson(exchange, """
                    {"results":[
                      {"title":"Old food news","url":"http://localhost:%s/old","content":"food news","publishedDate":"%s"},
                      {"title":"Undated food archive","url":"http://localhost:%s/archive","content":"food news"}
                    ]}
                    """.formatted(server.getAddress().getPort(), Instant.now().minusSeconds(172_800), server.getAddress().getPort()));
        });
        server.createContext("/old", exchange -> {
            pageRequests.incrementAndGet();
            respondHtml(exchange, "<main>old food news</main>");
        });
        server.start();
        try {
            WebSearchResponse response = new WebSearchService(properties(server, true)).searchDetailed(new WebSearchCommand(
                    "today food news", 5, WebSearchMode.NEWS, WebSearchFreshness.ANY, List.of(), List.of(), true));

            assertThat(requestedQuery.get()).contains("categories=news").contains("time_range=day");
            assertThat(response.results()).isEmpty();
            assertThat(response.trace().freshnessFilteredCount()).isEqualTo(1);
            assertThat(response.trace().pagesRead()).isEqualTo(2);
            assertThat(pageRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsCurrentNewsWhenThePageProvidesItsPublicationDate() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, """
                {"results":[{"title":"Food update","url":"http://localhost:%s/article","content":"food update"}]}
                """.formatted(server.getAddress().getPort())));
        server.createContext("/article", exchange -> respondHtml(exchange, """
                <html><head><meta property="article:published_time" content="%s"></head>
                <body><article>Food update with current restaurant news.</article></body></html>
                """.formatted(Instant.now())));
        server.start();
        try {
            WebSearchResponse response = new WebSearchService(properties(server, true)).searchDetailed(new WebSearchCommand(
                    "food news", 5, WebSearchMode.NEWS, WebSearchFreshness.ANY, List.of(), List.of(), true));

            assertThat(response.results()).singleElement().satisfies(result -> {
                assertThat(result.publishedAt()).isNotNull();
                assertThat(result.evidence().readable()).isTrue();
            });
            assertThat(response.trace().freshnessFilteredCount()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void excludesCurrentNewsCandidatesWhoseVerifiedPageDoesNotMatchTheTopic() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, """
                {"results":[{"title":"Shred Fest 2026","url":"http://localhost:%s/festival","content":"Music festival tickets","publishedDate":"%s"}]}
                """.formatted(server.getAddress().getPort(), Instant.now())));
        server.createContext("/festival", exchange -> respondHtml(exchange,
                "<html><title>Shred Fest 2026</title><article>Festival tickets and concert times.</article></html>"));
        server.start();
        try {
            WebSearchResponse response = new WebSearchService(properties(server, true)).searchDetailed(new WebSearchCommand(
                    "today AI news", 5, WebSearchMode.NEWS, WebSearchFreshness.DAY, List.of(), List.of(), true));

            assertThat(response.results()).isEmpty();
            assertThat(response.trace().verifiedPages()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsCurrentAiNewsWhenThePageOnlyUsesTheAiTermInEnglish() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, """
                {"results":[{"title":"AI model release","url":"http://localhost:%s/ai","content":"AI model update","publishedDate":"%s"}]}
                """.formatted(server.getAddress().getPort(), Instant.now())));
        server.createContext("/ai", exchange -> respondHtml(exchange, """
                <html><head><meta property="article:published_time" content="%s"></head>
                <body><article>AI model release and benchmark update.</article></body></html>
                """.formatted(Instant.now())));
        server.start();
        try {
            WebSearchResponse response = new WebSearchService(properties(server, true)).searchDetailed(new WebSearchCommand(
                    "今天值得关注的 AI 动态", 5, WebSearchMode.NEWS, WebSearchFreshness.DAY, List.of(), List.of(), true));

            assertThat(response.results()).singleElement().extracting(WebSearchResult::title)
                    .isEqualTo("AI model release");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void supplementsNewsWithGdeltPublicationDates() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, "{\"results\":[]}"));
        server.createContext("/gdelt", exchange -> respondJson(exchange, """
                {"articles":[{"title":"Food market update","url":"http://localhost:%s/article","domain":"news.example","seendate":"%s"}]}
                """.formatted(server.getAddress().getPort(),
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.now()))));
        server.createContext("/article", exchange -> respondHtml(exchange,
                "<article>Food market update from a current source.</article>"));
        server.start();
        try {
            String endpoint = "http://localhost:" + server.getAddress().getPort();
            AppProperties.NewsSources sources = new AppProperties.NewsSources(true, endpoint + "/gdelt", false, "");
            WebSearchResponse response = new WebSearchService(properties(server, true, sources)).searchDetailed(new WebSearchCommand(
                    "food news", 5, WebSearchMode.NEWS, WebSearchFreshness.ANY, List.of(), List.of(), true));

            assertThat(response.trace().providers()).extracting(WebSearchProviderTrace::sourceId)
                    .contains("searxng/news", "gdelt/doc");
            assertThat(response.results()).singleElement().extracting(WebSearchResult::sourceType).isEqualTo("GDELT");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksPrivateResultPagesUnlessExplicitlyAllowed() throws Exception {
        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, """
                {"results":[{"title":"Private page","url":"http://localhost:%s/private","content":"private result"}]}
                """.formatted(server.getAddress().getPort())));
        server.createContext("/private", exchange -> {
            pageRequests.incrementAndGet();
            respondHtml(exchange, "<main>Private result</main>");
        });
        server.start();
        try {
            WebSearchResponse response = new WebSearchService(properties(server, false)).searchDetailed(new WebSearchCommand("private", 3));

            assertThat(pageRequests).hasValue(0);
            assertThat(response.results().getFirst().evidence()).isNotNull();
            assertThat(response.results().getFirst().evidence().verification()).contains("Private or local");
            assertThat(response.trace().pagesRead()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void followsOnlyValidatedPageRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, """
                {"results":[{"title":"Spring redirect","url":"http://localhost:%s/redirect","content":"Spring redirect"}]}
                """.formatted(server.getAddress().getPort())));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/page", exchange -> respondHtml(exchange,
                "<html><title>Spring redirect</title><main>Spring redirect target content.</main></html>"));
        server.start();
        try {
            WebSearchResponse response = new WebSearchService(properties(server, true)).searchDetailed(new WebSearchCommand("Spring redirect", 3));

            assertThat(response.results().getFirst().evidence())
                    .extracting(WebEvidence::readable, WebEvidence::relevant)
                    .containsExactly(true, true);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canonicalizesTrackingUrlsAndDiversifiesDomains() {
        List<WebSearchResult> candidates = List.of(
                result("Spring guide", "https://docs.example.com/guide?utm_source=newsletter"),
                result("Spring guide mirror", "https://docs.example.com/guide"),
                result("Spring reference", "https://docs.example.com/reference"),
                result("Spring blog", "https://blog.example.org/spring"),
                result("Spring community", "https://community.example.net/spring"));

        WebSearchService.RankingResult ranking = WebSearchService.rankCandidates(
                candidates, "spring", 3, 1, 3, List.of(), List.of(), WebSearchFreshness.ANY);

        assertThat(ranking.duplicateCount()).isEqualTo(1);
        assertThat(ranking.results()).extracting(WebSearchResult::url)
                .containsExactlyInAnyOrder("https://docs.example.com/guide", "https://blog.example.org/spring", "https://community.example.net/spring");
    }

    @Test
    void cachesProviderResultsForImmediateRetries() throws Exception {
        AtomicInteger searchRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            searchRequests.incrementAndGet();
            respondJson(exchange, """
                    {"results":[{"title":"Spring cache guide","url":"https://docs.example/cache","content":"Spring cache"}]}
                    """);
        });
        server.start();
        try {
            WebSearchService service = new WebSearchService(properties(server, false));
            WebSearchCommand command = new WebSearchCommand(
                    "Spring cache", 3, WebSearchMode.TECHNICAL, WebSearchFreshness.ANY,
                    List.of(), List.of(), true);

            WebSearchResponse first = service.searchDetailed(command);
            WebSearchResponse second = service.searchDetailed(command);

            assertThat(first.trace().providers()).singleElement()
                    .extracting(WebSearchProviderTrace::status).isEqualTo("SUCCESS");
            assertThat(second.trace().providers()).singleElement()
                    .extracting(WebSearchProviderTrace::status).isEqualTo("CACHED");
            assertThat(searchRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotCacheTransientEmptyProviderResponses() throws Exception {
        AtomicInteger searchRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            int attempt = searchRequests.incrementAndGet();
            respondJson(exchange, attempt == 1 ? "{\"results\":[]}" : """
                    {"results":[{"title":"Recovered result","url":"https://docs.example/recovered","content":"recovered"}]}
                    """);
        });
        server.start();
        try {
            WebSearchService service = new WebSearchService(properties(server, false));
            WebSearchCommand command = new WebSearchCommand(
                    "recovered", 3, WebSearchMode.TECHNICAL, WebSearchFreshness.ANY,
                    List.of(), List.of(), true);

            assertThat(service.searchDetailed(command).results()).isEmpty();
            assertThat(service.searchDetailed(command).results()).singleElement()
                    .extracting(WebSearchResult::title).isEqualTo("Recovered result");
            assertThat(searchRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesRelativeIndexTimesAndCalendarDatesFromNewsUrls() {
        Instant now = Instant.parse("2026-07-31T12:00:00Z");

        assertThat(WebSearchService.parseRelativePublicationDate("7 hours ago | Example", now))
                .isEqualTo(now.minus(Duration.ofHours(7)));
        assertThat(WebSearchService.parseRelativePublicationDate("2小时前 | 示例", now))
                .isEqualTo(now.minus(Duration.ofHours(2)));
        assertThat(WebSearchService.parsePublicationDateFromUrl(
                "https://example.com/news/2026/07/31/story.html"))
                .isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
        assertThat(WebSearchService.parsePublicationDateFromUrl(
                "https://example.com/article/20260731A05EHB00"))
                .isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
    }

    private static AppProperties properties(HttpServer server, boolean allowPrivateHosts) {
        return properties(server, allowPrivateHosts, new AppProperties.NewsSources(false, "", false, ""));
    }

    private static AppProperties properties(
            HttpServer server,
            boolean allowPrivateHosts,
            AppProperties.NewsSources newsSources) {
        String endpoint = "http://localhost:" + server.getAddress().getPort();
        return new AppProperties(null, null, null,
                new AppProperties.WebSearch(true, 5, endpoint, 2, 3, "en", 1,
                        new AppProperties.PageReader(true, 3, 1_000, 100_000, allowPrivateHosts),
                        newsSources,
                        new AppProperties.SearchPlanning(1, 90)),
                null, null, null);
    }

    private static WebSearchResult result(String title, String url) {
        return new WebSearchResult(title, url, "spring", "searxng", "SEARXNG", null);
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
