package io.github.yourname.agentstudio.tool;

import io.github.yourname.agentstudio.config.AppProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Performs low-risk web search for the local agent runtime.
 *
 * <p>The first implementation intentionally uses a public HTML search endpoint
 * instead of asking the user for another paid API key. Search results are
 * treated as untrusted evidence: they may help the model answer, but they do
 * not override platform instructions, tenant identity, or tool policy.
 */
@Service
public class WebSearchService {

    private final AppProperties properties;
    private final RestClient.Builder restClientBuilder;

    public WebSearchService(AppProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    public List<WebSearchResult> search(WebSearchCommand command) {
        AppProperties.WebSearch config = properties.webSearch();
        if (config != null && !config.enabled()) {
            return List.of();
        }

        int configuredLimit = config == null || config.maxResults() <= 0 ? 5 : config.maxResults();
        int limit = command.limit() == null || command.limit() <= 0
                ? configuredLimit
                : Math.min(command.limit(), configuredLimit);
        String endpoint = config == null || config.endpoint() == null || config.endpoint().isBlank()
                ? "https://html.duckduckgo.com/html/"
                : config.endpoint();

        String html = restClientBuilder.build()
                .get()
                .uri(endpoint + "?q=" + URLEncoder.encode(command.query(), StandardCharsets.UTF_8))
                .header("User-Agent", "SpringAgentStudio/0.1 (+local-development)")
                .retrieve()
                .body(String.class);

        if (html == null || html.isBlank()) {
            return List.of();
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
}
