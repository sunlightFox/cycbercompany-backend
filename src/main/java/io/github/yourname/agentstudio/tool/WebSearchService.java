package io.github.yourname.agentstudio.tool;

import io.github.yourname.agentstudio.config.AppProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Service;

/**
 * Performs low-risk web search for the local agent runtime.
 *
 * <p>This service deliberately avoids requiring another paid search API key for
 * the learning/demo version. News/current-information queries use Google News
 * RSS first because it is stable and machine-readable. General web queries use
 * DuckDuckGo HTML as a best-effort fallback, but bot-challenge pages are
 * detected and ignored.
 *
 * <p>Search results are treated as untrusted evidence: they may help the model
 * answer, but they do not override platform instructions, tenant identity, or
 * tool policy.
 */
@Service
public class WebSearchService {

    private static final String DEFAULT_DUCKDUCKGO_ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final String BING_NEWS_RSS_ENDPOINT = "https://www.bing.com/news/search";
    private static final Map<String, String> CURATED_NEWS_FEEDS = Map.of(
            "BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml",
            "The Verge", "https://www.theverge.com/rss/index.xml",
            "TechCrunch", "https://techcrunch.com/feed/");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final AppProperties properties;
    private final HttpClient httpClient;

    public WebSearchService(AppProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<WebSearchResult> search(WebSearchCommand command) {
        AppProperties.WebSearch config = properties.webSearch();
        if (config != null && !config.enabled()) {
            return List.of();
        }

        int limit = resolveLimit(command, config);
        String query = command.query().trim();

        List<Exception> failures = new ArrayList<>();
        if (isNewsLikeQuery(query)) {
            List<WebSearchResult> newsResults = tryProvider(() -> searchBingNews(query, limit), failures);
            if (!newsResults.isEmpty()) {
                return newsResults;
            }
            List<WebSearchResult> genericNewsResults = tryProvider(() -> searchBingNews("latest news", limit), failures);
            if (!genericNewsResults.isEmpty()) {
                return genericNewsResults;
            }
            List<WebSearchResult> curatedNewsResults = tryProvider(() -> searchCuratedNewsFeeds(limit), failures);
            if (!curatedNewsResults.isEmpty()) {
                return curatedNewsResults;
            }
            throw new IllegalStateException("News search providers failed: " + summarizeFailures(failures));
        }

        List<WebSearchResult> webResults = tryProvider(() -> searchDuckDuckGo(query, limit, config), failures);
        if (!webResults.isEmpty()) {
            return webResults;
        }

        List<WebSearchResult> fallbackNewsResults = tryProvider(() -> searchBingNews(query, limit), failures);
        if (!fallbackNewsResults.isEmpty()) {
            return fallbackNewsResults;
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("All web search providers failed: " + summarizeFailures(failures));
        }
        return List.of();
    }

    private List<WebSearchResult> searchDuckDuckGo(String query, int limit, AppProperties.WebSearch config) {
        String endpoint = config == null || config.endpoint() == null || config.endpoint().isBlank()
                ? DEFAULT_DUCKDUCKGO_ENDPOINT
                : config.endpoint();

        String html = get(endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));

        if (html == null || html.isBlank()) {
            return List.of();
        }
        String normalizedHtml = html.toLowerCase(Locale.ROOT);
        if (normalizedHtml.contains("anomaly-modal")
                || normalizedHtml.contains("unfortunately, bots use duckduckgo too")
                || normalizedHtml.contains("challenge-form")) {
            throw new IllegalStateException("DuckDuckGo returned an anti-bot challenge page");
        }

        return Jsoup.parse(html).select(".result").stream()
                .map(result -> {
                    var link = result.selectFirst(".result__a");
                    if (link == null) {
                        return null;
                    }
                    String title = link.text();
                    String url = normalizeDuckDuckGoUrl(link.attr("href"));
                    String snippet = result.select(".result__snippet").text();
                    return new WebSearchResult(title, url, snippet);
                })
                .filter(item -> item != null && item.url() != null && !item.url().isBlank())
                .limit(limit)
                .toList();
    }

    private List<WebSearchResult> searchBingNews(String query, int limit) {
        String rss = get(BING_NEWS_RSS_ENDPOINT
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=rss");

        return parseRss(rss, limit);
    }

    private List<WebSearchResult> searchCuratedNewsFeeds(int limit) {
        Map<String, WebSearchResult> deduplicated = new LinkedHashMap<>();
        for (var feed : CURATED_NEWS_FEEDS.entrySet()) {
            try {
                for (WebSearchResult result : parseRss(get(feed.getValue()), limit)) {
                    String snippet = result.snippet().isBlank()
                            ? "source=" + feed.getKey()
                            : result.snippet() + ", source=" + feed.getKey();
                    deduplicated.putIfAbsent(result.url(), new WebSearchResult(result.title(), result.url(), snippet));
                    if (deduplicated.size() >= limit) {
                        return List.copyOf(deduplicated.values());
                    }
                }
            } catch (Exception ignored) {
                // Public RSS feeds are best-effort. One slow or blocked feed
                // should not prevent the remaining feeds from supplying evidence.
            }
        }
        return List.copyOf(deduplicated.values());
    }

    private List<WebSearchResult> parseRss(String rss, int limit) {

        if (rss == null || rss.isBlank()) {
            return List.of();
        }

        return Jsoup.parse(rss, "", Parser.xmlParser()).select("item").stream()
                .map(item -> {
                    String title = item.selectFirst("title") == null ? "" : item.selectFirst("title").text();
                    String url = item.selectFirst("link") == null ? "" : item.selectFirst("link").text();
                    String source = item.selectFirst("source") == null ? "" : item.selectFirst("source").text();
                    String pubDate = item.selectFirst("pubDate") == null ? "" : item.selectFirst("pubDate").text();
                    String snippet = (source.isBlank() ? "" : "source=" + source)
                            + (pubDate.isBlank() ? "" : (source.isBlank() ? "" : ", ") + "published=" + pubDate);
                    return new WebSearchResult(title, url, snippet);
                })
                .filter(item -> !item.title().isBlank() && !item.url().isBlank())
                .limit(limit)
                .toList();
    }

    private String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting " + url, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Request failed for " + url + ": " + ex.getMessage(), ex);
        }
    }

    private static int resolveLimit(WebSearchCommand command, AppProperties.WebSearch config) {
        int configuredLimit = config == null || config.maxResults() <= 0 ? 5 : config.maxResults();
        return command.limit() == null || command.limit() <= 0
                ? configuredLimit
                : Math.min(command.limit(), configuredLimit);
    }

    private static boolean isNewsLikeQuery(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("\u65b0\u95fb")
                || normalized.contains("\u8d44\u8baf")
                || normalized.contains("\u6700\u65b0")
                || normalized.contains("\u4eca\u65e5")
                || normalized.contains("\u4eca\u5929")
                || normalized.contains("news")
                || normalized.contains("latest")
                || normalized.contains("today");
    }

    private static List<WebSearchResult> tryProvider(SearchProvider provider, List<Exception> failures) {
        try {
            return provider.search();
        } catch (Exception ex) {
            failures.add(ex);
            return List.of();
        }
    }

    private static String summarizeFailures(List<Exception> failures) {
        return failures.stream()
                .map(ex -> ex.getClass().getSimpleName() + ": " + ex.getMessage())
                .reduce((left, right) -> left + "; " + right)
                .orElse("unknown error");
    }

    private static String normalizeDuckDuckGoUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        try {
            URI uri = URI.create(rawUrl.startsWith("//") ? "https:" + rawUrl : rawUrl);
            String query = uri.getRawQuery();
            if (query == null) {
                return rawUrl;
            }
            for (String part : query.split("&")) {
                int separator = part.indexOf('=');
                if (separator > 0 && "uddg".equals(part.substring(0, separator))) {
                    return URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException ignored) {
            return rawUrl;
        }
        return rawUrl;
    }

    @FunctionalInterface
    private interface SearchProvider {
        List<WebSearchResult> search();
    }
}
