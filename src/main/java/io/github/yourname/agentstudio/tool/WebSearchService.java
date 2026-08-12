package io.github.yourname.agentstudio.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Agent-oriented web retrieval using the configured Tavily API.
 *
 * <p>Search results are discovery candidates. Only readable, relevant page excerpts are
 * marked as verified evidence; both remain untrusted external content.
 */
@Service
public class WebSearchService {

    private static final String TAVILY_SOURCE = "tavily";
    private static final String TAVILY_DEFAULT_ENDPOINT = "https://api.tavily.com/search";
    private static final String USER_AGENT = "AgentStudio/1.0 (+local web retrieval)";
    private static final Set<String> TRACKING_PARAMETERS = Set.of("fbclid", "gclid", "mc_cid", "mc_eid");
    private static final Pattern ENGLISH_RELATIVE_TIME = Pattern.compile(
            "(?i)(\\d+)\\s*(minute|hour|day)s?\\s+ago");
    private static final Pattern CHINESE_RELATIVE_TIME = Pattern.compile("(\\d+)\\s*(分钟|小时|天)前");
    private static final Pattern URL_CALENDAR_DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2})[-/]?(0[1-9]|1[0-2])[-/]?(0[1-9]|[12]\\d|3[01])(?!\\d)");

    private final AppProperties properties;
    private final HttpClient searchHttpClient;
    private final HttpClient pageHttpClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CachedSearch> searchCache = new ConcurrentHashMap<>();

    @Autowired
    public WebSearchService(AppProperties properties, ObjectMapper objectMapper) {
        this(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build(),
                objectMapper);
    }

    WebSearchService(AppProperties properties) {
        this(properties, new ObjectMapper());
    }

    WebSearchService(AppProperties properties, HttpClient httpClient) {
        this(properties, httpClient, httpClient, new ObjectMapper());
    }

    WebSearchService(
            AppProperties properties,
            HttpClient searchHttpClient,
            HttpClient pageHttpClient,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.searchHttpClient = searchHttpClient;
        this.pageHttpClient = pageHttpClient;
        this.objectMapper = objectMapper;
    }

    /** Backward-compatible result-only API for callers that do not need diagnostics. */
    public List<WebSearchResult> search(WebSearchCommand command) {
        return searchDetailed(command).results();
    }

    public WebSearchResponse searchDetailed(WebSearchCommand command) {
        AppProperties.WebSearch config = effectiveConfig(properties.webSearch());
        String query = command.query().trim();
        WebSearchMode intent = resolveIntent(command, query);
        if (!config.enabled()) {
            return response(query, intent, List.of(), List.of(), 0, 0, 0, 0, 0, 0);
        }

        int limit = resolveLimit(command, config);
        String topicQuery = topicQuery(query);
        WebSearchFreshness effectiveFreshness = effectiveFreshness(intent, command.freshness());
        boolean requirePublicationDate = requiresPublicationDate(intent, effectiveFreshness);
        int candidateLimit = Math.min(30, Math.max(limit * 3, pageReadLimit(config.pageReader())));
        AppProperties.SearchPlanning planning = effectivePlanning(config.planning());
        List<WebSearchQueryPlanner.PlannedQuery> queryPlan = WebSearchQueryPlanner.plan(
                query, topicQuery, intent, planning.maxQueries());
        List<SearchAttempt> attempts = executeSearchPlan(
                config, command, queryPlan, candidateLimit, planning.cacheTtlSeconds());
        List<WebSearchProviderTrace> traces = attempts.stream().map(SearchAttempt::trace).collect(Collectors.toCollection(ArrayList::new));
        List<WebSearchResult> candidates = attempts.stream()
                .flatMap(attempt -> attempt.results().stream())
                .collect(Collectors.toCollection(ArrayList::new));
        // Retry the same user query against the general category when a news-style
        // search returns no candidates and query fan-out is disabled.
        if (candidates.isEmpty() && intent == WebSearchMode.NEWS
                && queryPlan.stream().noneMatch(item -> item.mode() == WebSearchMode.GENERAL)) {
            SearchAttempt fallback = searchTavily(config, command, query, WebSearchMode.GENERAL, candidateLimit,
                    "tavily/general-fallback", planning.cacheTtlSeconds());
            traces.add(fallback.trace());
            candidates = fallback.results();
        }
        RankingResult candidatesForReading = rankCandidates(
                candidates, topicQuery, candidateLimit, config.perDomainLimit(), config.minUniqueDomains(),
                command.includeDomains(), command.excludeDomains(), WebSearchFreshness.ANY, false, requirePublicationDate);
        EvidenceReadResult evidence = readEvidence(candidatesForReading.results(), topicQuery, config.pageReader());
        List<WebSearchResult> rankedCandidates = requirePublicationDate
                ? evidence.results().stream().filter(WebSearchService::isVerifiedRelevantEvidence).toList()
                : evidence.results();
        RankingResult ranking = rankCandidates(
                rankedCandidates, topicQuery, limit, config.perDomainLimit(), config.minUniqueDomains(),
                command.includeDomains(), command.excludeDomains(), effectiveFreshness, requirePublicationDate,
                requirePublicationDate);
        return response(query, intent, ranking.results(), traces, candidatesForReading.duplicateCount(),
                ranking.domainLimitedCount(), ranking.uniqueDomainCount(), ranking.freshnessFilteredCount(),
                evidence.pagesRead(), evidence.verifiedPages());
    }

    private List<SearchAttempt> executeSearchPlan(
            AppProperties.WebSearch config,
            WebSearchCommand command,
            List<WebSearchQueryPlanner.PlannedQuery> queryPlan,
            int limit,
            long cacheTtlSeconds) {
        List<Future<SearchAttempt>> tasks = new ArrayList<>();
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (WebSearchQueryPlanner.PlannedQuery planned : queryPlan) {
                tasks.add(executor.submit(() -> searchTavily(
                        config, command, planned.query(), planned.mode(), limit, planned.sourceId(), cacheTtlSeconds)));
            }
            List<SearchAttempt> attempts = new ArrayList<>(tasks.size());
            for (Future<SearchAttempt> task : tasks) {
                try {
                    attempts.add(task.get());
                } catch (Exception ex) {
                    attempts.add(failedAttempt("search/worker", "", System.nanoTime(), safeMessage(ex)));
                }
            }
            return List.copyOf(attempts);
        }
    }

    private SearchAttempt searchTavily(
            AppProperties.WebSearch config,
            WebSearchCommand command,
            String query,
            WebSearchMode intent,
            int limit,
            String sourceId,
            long cacheTtlSeconds) {
        long startedAt = System.nanoTime();
        String providerQuery = query;
        try {
            URI uri = buildTavilyUri(config);
            String body = buildTavilyRequestBody(providerQuery, command, intent, limit);
            List<WebSearchResult> cached = cachedResults("tavily:" + body, cacheTtlSeconds);
            if (cached != null) {
                return cachedAttempt(sourceId, providerQuery, startedAt, cached);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.tavilyApiKey())
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = searchHttpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Tavily returned HTTP " + response.statusCode()
                        + (response.body() == null || response.body().isBlank() ? "" : ": " + safeTavilyError(response.body())));
            }
            List<WebSearchResult> results = parseTavilyResults(response.body(), limit);
            cacheResults("tavily:" + body, results, cacheTtlSeconds);
            return new SearchAttempt(results, new WebSearchProviderTrace(
                    sourceId, "SUCCESS", providerQuery, results.size(), elapsedMillis(startedAt), ""));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failedAttempt(sourceId, providerQuery, startedAt,
                    "endpoint=" + safeEndpoint(TAVILY_DEFAULT_ENDPOINT) + ": Tavily request was interrupted");
        } catch (Exception ex) {
            return failedAttempt(sourceId, providerQuery, startedAt,
                    "endpoint=" + safeEndpoint(TAVILY_DEFAULT_ENDPOINT) + ": " + safeMessage(ex));
        }
    }

    private SearchAttempt failedAttempt(String sourceId, String query, long startedAt, String detail) {
        return new SearchAttempt(List.of(), new WebSearchProviderTrace(
                sourceId, "FAILED", query, 0, elapsedMillis(startedAt), detail));
    }

    private SearchAttempt cachedAttempt(String sourceId, String query, long startedAt, List<WebSearchResult> results) {
        return new SearchAttempt(results, new WebSearchProviderTrace(
                sourceId, "CACHED", query, results.size(), elapsedMillis(startedAt), "Short-lived provider cache"));
    }

    private List<WebSearchResult> cachedResults(String key, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return null;
        }
        CachedSearch cached = searchCache.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt().isBefore(Instant.now())) {
            searchCache.remove(key, cached);
            return null;
        }
        return cached.results();
    }

    private void cacheResults(String key, List<WebSearchResult> results, long ttlSeconds) {
        if (ttlSeconds <= 0 || results.isEmpty()) {
            return;
        }
        searchCache.put(key, new CachedSearch(List.copyOf(results), Instant.now().plusSeconds(Math.min(600, ttlSeconds))));
        if (searchCache.size() > 256) {
            Instant now = Instant.now();
            searchCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        }
    }

    private List<WebSearchResult> parseTavilyResults(String json, int limit) throws Exception {
        JsonNode results = objectMapper.readTree(json).path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("Tavily JSON response did not contain a results array");
        }
        List<WebSearchResult> parsed = new ArrayList<>();
        for (JsonNode item : results) {
            String title = item.path("title").asText("").trim();
            String url = item.path("url").asText("").trim();
            if (title.isBlank() || url.isBlank()) {
                continue;
            }
            Instant publishedAt = parsePublicationDate(item.path("published_date").asText(""));
            if (publishedAt == null) {
                publishedAt = parsePublicationDate(item.path("publishedDate").asText(""));
            }
            if (publishedAt == null) {
                publishedAt = parsePublicationDateFromUrl(url);
            }
            parsed.add(new WebSearchResult(title, url, item.path("content").asText(""), TAVILY_SOURCE,
                    "TAVILY", publishedAt));
            if (parsed.size() >= limit) {
                break;
            }
        }
        return List.copyOf(parsed);
    }

    private EvidenceReadResult readEvidence(
            List<WebSearchResult> results,
            String query,
            AppProperties.PageReader pageReader) {
        if (pageReader == null || !pageReader.enabled() || pageReader.maxResults() <= 0) {
            return new EvidenceReadResult(results, 0, 0);
        }
        List<WebSearchResult> enriched = new ArrayList<>(results.size());
        int pagesRead = 0;
        int verifiedPages = 0;
        int readLimit = Math.min(results.size(), pageReader.maxResults());
        List<Future<PageRead>> pageTasks = new ArrayList<>(readLimit);
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < readLimit; index++) {
                WebSearchResult result = results.get(index);
                pageTasks.add(executor.submit(() -> readPage(result.url(), query, pageReader)));
            }
            for (int index = 0; index < results.size(); index++) {
                WebSearchResult result = results.get(index);
                if (index >= readLimit) {
                    enriched.add(result);
                    continue;
                }
                PageRead page;
                try {
                    page = pageTasks.get(index).get();
                } catch (Exception ex) {
                    page = new PageRead(true, WebEvidence.unreadable("Page reader task failed: " + safeMessage(ex)));
                }
                pagesRead += page.attempted() ? 1 : 0;
                verifiedPages += page.evidence().readable() && page.evidence().relevant() ? 1 : 0;
                enriched.add(withEvidence(result, page.evidence()));
            }
        }
        return new EvidenceReadResult(List.copyOf(enriched), pagesRead, verifiedPages);
    }

    private PageRead readPage(
            String rawUrl,
            String query,
            AppProperties.PageReader settings) {
        return readDirectPage(rawUrl, query, settings);
    }

    private PageRead readDirectPage(String rawUrl, String query, AppProperties.PageReader settings) {
        boolean requested = false;
        try {
            URI uri = validatePageUri(rawUrl, settings.allowPrivateHosts());
            HttpResponse<InputStream> response = null;
            for (int redirectCount = 0; redirectCount < 3; redirectCount++) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                requested = true;
                response = pageHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 300 || response.statusCode() >= 400) {
                    break;
                }
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElse("");
                if (location.isBlank()) {
                    return new PageRead(true, WebEvidence.unreadable("Page redirected without a location"));
                }
                uri = validatePageUri(uri.resolve(location).toString(), settings.allowPrivateHosts());
            }
            if (response == null) {
                return new PageRead(true, WebEvidence.unreadable("Page could not be requested"));
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                closeQuietly(response.body());
                return new PageRead(true, WebEvidence.unreadable("Page exceeded the redirect limit"));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                return new PageRead(true, WebEvidence.unreadable("Page returned HTTP " + response.statusCode()));
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.isBlank() && !contentType.contains("text/html") && !contentType.contains("application/xhtml")
                    && !contentType.contains("text/plain")) {
                closeQuietly(response.body());
                return new PageRead(true, WebEvidence.unreadable("Page did not return readable HTML or text"));
            }
            String body;
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(Math.max(1, settings.maxResponseBytes()));
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            WebEvidence evidence = extractEvidence(body, uri.toString(), query, settings.maxExcerptChars());
            return new PageRead(true, evidence);
        } catch (Exception ex) {
            return new PageRead(requested, WebEvidence.unreadable(safeMessage(ex)));
        }
    }

    /*
    private PageRead readViaJina(
            String rawUrl,
            String query,
            AppProperties.PageReader settings,
            AppProperties.NewsSources newsSources) {
        try {
            validatePageUri(rawUrl, settings.allowPrivateHosts());
            String endpoint = blankToDefault(newsSources.jinaReaderEndpoint(), "https://r.jina.ai/");
            URI uri = URI.create(endpoint.endsWith("/") ? endpoint + rawUrl : endpoint + "/" + rawUrl);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "text/plain")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = pageHttpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new PageRead(true, WebEvidence.unreadable("Jina Reader returned HTTP " + response.statusCode()));
            }
            String text = normalizeText(response.body());
            if (text.isBlank()) {
                return new PageRead(true, WebEvidence.unreadable("Jina Reader returned no readable text"));
            }
            String excerpt = text.substring(0, Math.min(Math.max(1, settings.maxExcerptChars()), text.length()));
            boolean relevant = matchesQueryText(excerpt, query);
            return new PageRead(true, new WebEvidence("", excerpt, true, relevant,
                    relevant ? "Jina Reader fallback text matched the query." : "Jina Reader fallback text had weak query overlap."));
        } catch (Exception ex) {
            return new PageRead(true, WebEvidence.unreadable("Jina Reader failed: " + safeMessage(ex)));
        }
    }

    */

    private WebEvidence extractEvidence(String html, String baseUri, String query, int maxExcerptChars) {
        Document document = Jsoup.parse(html, baseUri);
        Instant publishedAt = extractPublicationDate(document);
        document.select("script,style,noscript,svg,nav,footer,header,aside,form").remove();
        Element primary = document.selectFirst("article, main, [role=main]");
        String text = normalizeText((primary == null ? document.body() : primary).text());
        if (text.isBlank()) {
            return WebEvidence.unreadable("Page did not contain readable body text");
        }
        String excerpt = text.substring(0, Math.min(Math.max(1, maxExcerptChars), text.length()));
        boolean relevant = matchesQueryText(document.title() + " " + excerpt, query);
        return new WebEvidence(document.title(), excerpt, true, relevant,
                relevant ? "Readable page text matched the query." : "Readable page text had weak query overlap.", publishedAt);
    }

    private URI buildTavilyUri(AppProperties.WebSearch config) {
        String apiKey = config.tavilyApiKey();
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Tavily API key is not configured");
        }
        String configuredEndpoint = blankToDefault(config.tavilyEndpoint(), TAVILY_DEFAULT_ENDPOINT);
        String normalizedEndpoint = configuredEndpoint.endsWith("/search")
                ? configuredEndpoint
                : (configuredEndpoint.endsWith("/") ? configuredEndpoint + "search" : configuredEndpoint + "/search");
        URI endpoint = URI.create(normalizedEndpoint);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("Tavily endpoint must use HTTP(S)");
        }
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Tavily endpoint must not contain a query or fragment");
        }
        return endpoint;
    }

    private String buildTavilyRequestBody(
            String query,
            WebSearchCommand command,
            WebSearchMode intent,
            int limit) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("search_depth", "basic");
        payload.put("topic", intent == WebSearchMode.NEWS ? "news" : "general");
        String timeRange = tavilyTimeRangeFor(intent, effectiveFreshness(intent, command.freshness()));
        if (timeRange != null) {
            payload.put("time_range", timeRange);
        }
        List<String> includeDomains = new ArrayList<>(normalizeDomains(command.includeDomains()));
        List<String> excludeDomains = new ArrayList<>(normalizeDomains(command.excludeDomains()));
        if (!includeDomains.isEmpty()) {
            payload.put("include_domains", includeDomains);
        }
        if (!excludeDomains.isEmpty()) {
            payload.put("exclude_domains", excludeDomains);
        }
        payload.put("max_results", Math.min(20, Math.max(1, limit)));
        payload.put("include_answer", false);
        payload.put("include_raw_content", false);
        payload.put("auto_parameters", false);
        return objectMapper.writeValueAsString(payload);
    }

    /*
    private static void addParameter(StringJoiner parameters, String key, String value) {
        parameters.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String categoryFor(WebSearchMode intent) {
        return switch (intent) {
            case NEWS -> "news";
            default -> "general";
        };
    }

    private static String timeRangeFor(WebSearchMode intent, WebSearchFreshness freshness) {
        return switch (effectiveFreshness(intent, freshness)) {
            case DAY -> "day";
            case MONTH -> "month";
            case WEEK -> "month";
            case ANY -> null;
        };
    }

    */

    private static WebSearchFreshness effectiveFreshness(WebSearchMode intent, WebSearchFreshness requested) {
        if (requested != null && requested != WebSearchFreshness.ANY) {
            return requested;
        }
        return intent == WebSearchMode.NEWS || intent == WebSearchMode.RECENT
                ? WebSearchFreshness.DAY
                : WebSearchFreshness.ANY;
    }

    private static boolean requiresPublicationDate(WebSearchMode intent, WebSearchFreshness freshness) {
        return freshness == WebSearchFreshness.DAY
                && (intent == WebSearchMode.NEWS || intent == WebSearchMode.RECENT);
    }

    private static String tavilyTimeRangeFor(WebSearchMode intent, WebSearchFreshness freshness) {
        return switch (effectiveFreshness(intent, freshness)) {
            case DAY -> "day";
            case WEEK -> "week";
            case MONTH -> "month";
            case ANY -> null;
        };
    }

    private static AppProperties.WebSearch effectiveConfig(AppProperties.WebSearch configured) {
        AppProperties.WebSearch defaults = new AppProperties.WebSearch(
                true, 5, TAVILY_DEFAULT_ENDPOINT, "", 2, 3,
                AppProperties.PageReader.defaults(), AppProperties.SearchPlanning.defaults());
        return configured == null ? defaults : configured;
    }

    private static String safeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "<empty>";
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            String authority = host == null ? "<invalid>" : host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            return uri.getScheme() + "://" + authority;
        } catch (Exception ignored) {
            return "<invalid>";
        }
    }

    private static String safeTavilyError(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            String detail = root.path("detail").asText("");
            if (!detail.isBlank()) {
                return detail;
            }
            String error = root.path("error").asText("");
            if (!error.isBlank()) {
                return error;
            }
            return body.substring(0, Math.min(240, body.length()));
        } catch (Exception ignored) {
            return body.substring(0, Math.min(240, body.length()));
        }
    }

    /*
    private static AppProperties.NewsSources effectiveNewsSources(AppProperties.NewsSources configured) {
        return configured == null ? AppProperties.NewsSources.defaults() : configured;
    }
    */

    private static AppProperties.SearchPlanning effectivePlanning(AppProperties.SearchPlanning configured) {
        return configured == null ? AppProperties.SearchPlanning.defaults() : new AppProperties.SearchPlanning(
                Math.min(4, Math.max(1, configured.maxQueries())),
                Math.min(600, Math.max(0, configured.cacheTtlSeconds())));
    }

    private static int pageReadLimit(AppProperties.PageReader pageReader) {
        return pageReader == null || !pageReader.enabled() ? 0 : Math.max(0, pageReader.maxResults());
    }

    private static WebSearchResponse response(
            String query,
            WebSearchMode intent,
            List<WebSearchResult> results,
            List<WebSearchProviderTrace> providers,
            int duplicateCount,
            int domainLimitedCount,
            int uniqueDomainCount,
            int freshnessFilteredCount,
            int pagesRead,
            int verifiedPages) {
        return new WebSearchResponse(query, intent, List.copyOf(results),
                new WebSearchTrace(List.copyOf(providers), duplicateCount, domainLimitedCount, uniqueDomainCount,
                        freshnessFilteredCount, pagesRead, verifiedPages));
    }

    static RankingResult rankCandidates(
            List<WebSearchResult> input,
            String query,
            int limit,
            int perDomainLimit,
            int minUniqueDomains,
            List<String> includeDomains,
            List<String> excludeDomains,
            WebSearchFreshness freshness) {
        return rankCandidates(input, query, limit, perDomainLimit, minUniqueDomains, includeDomains, excludeDomains, freshness, false);
    }

    static RankingResult rankCandidates(
            List<WebSearchResult> input,
            String query,
            int limit,
            int perDomainLimit,
            int minUniqueDomains,
            List<String> includeDomains,
            List<String> excludeDomains,
            WebSearchFreshness freshness,
            boolean requirePublicationDate) {
        return rankCandidates(input, query, limit, perDomainLimit, minUniqueDomains, includeDomains, excludeDomains,
                freshness, requirePublicationDate, false);
    }

    static RankingResult rankCandidates(
            List<WebSearchResult> input,
            String query,
            int limit,
            int perDomainLimit,
            int minUniqueDomains,
            List<String> includeDomains,
            List<String> excludeDomains,
            WebSearchFreshness freshness,
            boolean requirePublicationDate,
            boolean preferFreshness) {
        Set<String> included = normalizeDomains(includeDomains);
        Set<String> excluded = normalizeDomains(excludeDomains);
        Map<String, WebSearchResult> unique = new LinkedHashMap<>();
        int duplicates = 0;
        int freshnessFiltered = 0;
        for (WebSearchResult result : input) {
            if (!isUsableResult(result)) {
                continue;
            }
            if (!matchesFreshness(result, freshness) || (requirePublicationDate && result.publishedAt() == null)) {
                freshnessFiltered++;
                continue;
            }
            String canonicalUrl = canonicalizeUrl(result.url());
            String domain = domainOf(canonicalUrl);
            if (!matchesDomainFilters(domain, included, excluded)) {
                continue;
            }
            if (unique.putIfAbsent(canonicalUrl, withUrl(result, canonicalUrl)) != null) {
                duplicates++;
            }
        }
        List<WebSearchResult> uniqueResults = new ArrayList<>(unique.values());
        List<ScoredResult> ranked = new ArrayList<>(uniqueResults.size());
        for (int index = 0; index < uniqueResults.size(); index++) {
            WebSearchResult result = uniqueResults.get(index);
            ranked.add(new ScoredResult(result, domainOf(result.url()),
                    rankingScore(result, query, preferFreshness), index));
        }
        ranked.sort(Comparator.comparingDouble(ScoredResult::score).reversed()
                .thenComparing(item -> item.result().publishedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(ScoredResult::sourceOrder));
        return selectDiverse(ranked, limit, Math.max(1, perDomainLimit), Math.max(1, minUniqueDomains), duplicates, freshnessFiltered);
    }

    private static RankingResult selectDiverse(
            List<ScoredResult> ranked, int limit, int perDomainLimit, int minUniqueDomains, int duplicateCount, int freshnessFilteredCount) {
        int targetUnique = Math.min(Math.min(limit, minUniqueDomains), (int) ranked.stream().map(ScoredResult::domain).distinct().count());
        List<WebSearchResult> selected = new ArrayList<>();
        Set<String> selectedUrls = new LinkedHashSet<>();
        Map<String, Integer> perDomain = new LinkedHashMap<>();
        for (ScoredResult candidate : ranked) {
            if (selected.size() >= targetUnique) break;
            if (!perDomain.containsKey(candidate.domain())) add(candidate, selected, selectedUrls, perDomain);
        }
        int domainLimited = 0;
        for (ScoredResult candidate : ranked) {
            if (selected.size() >= limit) break;
            if (selectedUrls.contains(candidate.result().url())) continue;
            if (perDomain.getOrDefault(candidate.domain(), 0) >= perDomainLimit) {
                domainLimited++;
                continue;
            }
            add(candidate, selected, selectedUrls, perDomain);
        }
        return new RankingResult(List.copyOf(selected), duplicateCount, domainLimited, perDomain.size(), freshnessFilteredCount);
    }

    private static void add(ScoredResult candidate, List<WebSearchResult> selected, Set<String> urls, Map<String, Integer> domains) {
        selected.add(candidate.result());
        urls.add(candidate.result().url());
        domains.merge(candidate.domain(), 1, Integer::sum);
    }

    private static URI validatePageUri(String rawUrl, boolean allowPrivateHosts) throws Exception {
        URI uri = URI.create(rawUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTP(S) result pages can be read");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Result page did not contain a host");
        }
        if (!allowPrivateHosts) {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPubliclyRoutableAddress(address)) {
                    throw new IllegalArgumentException("Private or local result hosts are not readable");
                }
            }
        }
        return uri;
    }

    static boolean isPubliclyRoutableAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            return isPublicIpv4(raw);
        }
        if (raw.length != 16) {
            return false;
        }
        // IPv4-compatible and IPv4-mapped IPv6 literals must inherit IPv4 restrictions.
        boolean ipv4Compatible = true;
        for (int index = 0; index < 12; index++) {
            if (raw[index] != 0) {
                ipv4Compatible = false;
                break;
            }
        }
        boolean ipv4Mapped = true;
        for (int index = 0; index < 10; index++) {
            if (raw[index] != 0) {
                ipv4Mapped = false;
                break;
            }
        }
        ipv4Mapped = ipv4Mapped && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff;
        if (ipv4Compatible || ipv4Mapped) {
            return isPublicIpv4(new byte[] { raw[12], raw[13], raw[14], raw[15] });
        }
        // fc00::/7 is IPv6 unique-local space. Java's isSiteLocalAddress does not cover it.
        return (raw[0] & 0xfe) != 0xfc;
    }

    private static boolean isPublicIpv4(byte[] raw) {
        int first = Byte.toUnsignedInt(raw[0]);
        int second = Byte.toUnsignedInt(raw[1]);
        return first != 0
                && first != 10
                && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 168)
                && !(first >= 224);
    }

    private static boolean isUsableResult(WebSearchResult result) {
        return result != null && result.title() != null && !result.title().isBlank()
                && result.url() != null && !result.url().isBlank();
    }

    private static boolean matchesFreshness(WebSearchResult result, WebSearchFreshness freshness) {
        if (freshness == null || freshness == WebSearchFreshness.ANY || result.publishedAt() == null) return true;
        Duration age = switch (freshness) {
            case DAY -> Duration.ofDays(1);
            case WEEK -> Duration.ofDays(7);
            case MONTH -> Duration.ofDays(31);
            case ANY -> Duration.ZERO;
        };
        return !result.publishedAt().isBefore(Instant.now().minus(age));
    }

    private Instant extractPublicationDate(Document document) {
        for (String selector : List.of(
                "meta[property='article:published_time']",
                "meta[property='og:published_time']",
                "meta[name=date]",
                "meta[name=publishdate]",
                "meta[itemprop=datePublished]",
                "time[datetime]")) {
            Element element = document.selectFirst(selector);
            if (element == null) {
                continue;
            }
            Instant parsed = parsePublicationDate(element.hasAttr("content") ? element.attr("content") : element.attr("datetime"));
            if (parsed != null) {
                return parsed;
            }
        }
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                Instant parsed = findPublicationDate(objectMapper.readTree(script.data()));
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // Malformed structured data is common; other date signals remain usable.
            }
        }
        return null;
    }

    private Instant findPublicationDate(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                Instant parsed = findPublicationDate(item);
                if (parsed != null) {
                    return parsed;
                }
            }
            return null;
        }
        if (!value.isObject()) {
            return null;
        }
        for (String field : List.of("datePublished", "dateCreated")) {
            Instant parsed = parsePublicationDate(value.path(field).asText(""));
            if (parsed != null) {
                return parsed;
            }
        }
        for (JsonNode item : value) {
            Instant parsed = findPublicationDate(item);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static boolean matchesQueryText(String text, String query) {
        String normalized = text.toLowerCase(Locale.ROOT);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        if (normalized.contains(normalizedQuery)) return true;
        List<String> tokens = queryTokens(normalizedQuery);
        long matches = tokens.stream().filter(normalized::contains).count();
        if (matches >= Math.min(2, Math.max(1, tokens.size()))) return true;
        if (tokens.contains("ai") && normalized.contains("ai")) return true;
        List<String> bigrams = chineseBigrams(normalizedQuery);
        long bigramMatches = bigrams.stream().filter(normalized::contains).count();
        return bigramMatches >= Math.min(2, Math.max(1, bigrams.size()));
    }

    private static boolean isVerifiedRelevantEvidence(WebSearchResult result) {
        return result.evidence() != null && result.evidence().readable() && result.evidence().relevant();
    }

    private static double relevanceScore(WebSearchResult result, String query) {
        String title = result.title().toLowerCase(Locale.ROOT);
        String snippet = result.snippet().toLowerCase(Locale.ROOT);
        String url = result.url().toLowerCase(Locale.ROOT);
        double score = 0;
        for (String token : queryTokens(query.toLowerCase(Locale.ROOT))) {
            if (title.contains(token)) score += 3;
            if (snippet.contains(token)) score += 1;
            if (url.contains(token)) score += 2;
        }
        return score;
    }

    private static double rankingScore(WebSearchResult result, String query, boolean preferFreshness) {
        double score = relevanceScore(result, query);
        if (preferFreshness && result.publishedAt() != null) {
            score += 8;
        }
        if (result.evidence() != null && result.evidence().readable() && result.evidence().relevant()) {
            score += 10;
        }
        return score;
    }

    private static List<String> queryTokens(String query) {
        return List.of(query.split("[^\\p{L}\\p{N}]+"))
                .stream().filter(token -> token.length() >= 2).distinct().toList();
    }

    private static String topicQuery(String query) {
        String topical = query == null ? "" : query
                .replaceAll("(?i)\\b(today|latest|current|news|search)\\b", " ")
                .replace("\u4eca\u65e5", " ")
                .replace("\u4eca\u5929", " ")
                .replace("\u6700\u65b0", " ")
                .replace("\u65b0\u95fb", " ")
                .replace("\u8d44\u8baf", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return topical;
    }

    private static List<String> chineseBigrams(String text) {
        List<String> bigrams = new ArrayList<>();
        for (int index = 0; index < text.length() - 1; index++) {
            char left = text.charAt(index);
            char right = text.charAt(index + 1);
            if (Character.UnicodeScript.of(left) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(right) == Character.UnicodeScript.HAN) {
                bigrams.add(text.substring(index, index + 2));
            }
        }
        return bigrams;
    }

    private static Set<String> normalizeDomains(List<String> values) {
        if (values == null) return Set.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("/.*$", ""))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean matchesDomainFilters(String domain, Set<String> included, Set<String> excluded) {
        if (matchesAnyDomain(domain, excluded)) return false;
        return included.isEmpty() || matchesAnyDomain(domain, included);
    }

    private static boolean matchesAnyDomain(String domain, Set<String> patterns) {
        return patterns.stream().anyMatch(pattern -> domain.equals(pattern) || domain.endsWith("." + pattern));
    }

    private static String canonicalizeUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            List<String> kept = uri.getRawQuery() == null ? List.of() : List.of(uri.getRawQuery().split("&")).stream()
                    .filter(part -> {
                        String key = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
                        return !key.toLowerCase(Locale.ROOT).startsWith("utm_")
                                && !TRACKING_PARAMETERS.contains(key.toLowerCase(Locale.ROOT));
                    }).toList();
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(),
                    kept.isEmpty() ? null : String.join("&", kept), null).toString();
        } catch (Exception ignored) {
            return rawUrl.trim();
        }
    }

    private static String domainOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? "unknown" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static WebSearchResult withUrl(WebSearchResult result, String url) {
        return new WebSearchResult(result.title(), url, result.snippet(), result.sourceId(), result.sourceType(), result.publishedAt(), result.evidence());
    }

    private static WebSearchResult withEvidence(WebSearchResult result, WebEvidence evidence) {
        Instant publishedAt = result.publishedAt() == null && evidence != null ? evidence.publishedAt() : result.publishedAt();
        return new WebSearchResult(result.title(), result.url(), result.snippet(), result.sourceId(), result.sourceType(), publishedAt, evidence);
    }

    private static Instant parsePublicationDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from);
            } catch (DateTimeParseException alsoIgnored) {
                try {
                    return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException dateOnlyIgnored) {
                    return null;
                }
            }
        }
    }

    static Instant parseRelativePublicationDate(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher english = ENGLISH_RELATIVE_TIME.matcher(value);
        if (english.find()) {
            long amount = Long.parseLong(english.group(1));
            return now.minus(switch (english.group(2).toLowerCase(Locale.ROOT)) {
                case "minute" -> Duration.ofMinutes(amount);
                case "hour" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            });
        }
        Matcher chinese = CHINESE_RELATIVE_TIME.matcher(value);
        if (chinese.find()) {
            long amount = Long.parseLong(chinese.group(1));
            return now.minus(switch (chinese.group(2)) {
                case "分钟" -> Duration.ofMinutes(amount);
                case "小时" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            });
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("yesterday") || value.contains("昨天")) {
            return now.minus(Duration.ofDays(1));
        }
        if (normalized.contains("today") || value.contains("今天") || value.contains("今日")) {
            return now;
        }
        return null;
    }

    static Instant parsePublicationDateFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher matcher = URL_CALENDAR_DATE.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)))
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static WebSearchMode resolveIntent(WebSearchCommand command, String query) {
        if (command.mode() != null && command.mode() != WebSearchMode.AUTO) return command.mode();
        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.contains("新闻") || normalized.contains("资讯") || normalized.contains("动态") || normalized.contains("news")) return WebSearchMode.NEWS;
        if (normalized.contains("最新") || normalized.contains("今日") || normalized.contains("今天")
                || normalized.contains("latest") || normalized.contains("today") || normalized.contains("recent")) return WebSearchMode.RECENT;
        if (normalized.contains("github") || normalized.contains("api") || normalized.contains("docs")
                || normalized.contains("文档") || normalized.contains("教程") || normalized.contains("spring")
                || normalized.contains("java") || normalized.contains("代码")) return WebSearchMode.TECHNICAL;
        return WebSearchMode.GENERAL;
    }

    private static int resolveLimit(WebSearchCommand command, AppProperties.WebSearch config) {
        int configured = config.maxResults() <= 0 ? 5 : config.maxResults();
        return command.limit() == null || command.limit() <= 0 ? configured : Math.min(command.limit(), configured);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // No useful recovery is possible while reporting a failed page read.
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(240, message.length()));
    }

    record RankingResult(
            List<WebSearchResult> results,
            int duplicateCount,
            int domainLimitedCount,
            int uniqueDomainCount,
            int freshnessFilteredCount) {
    }

    private record SearchAttempt(List<WebSearchResult> results, WebSearchProviderTrace trace) {
    }

    private record CachedSearch(List<WebSearchResult> results, Instant expiresAt) {
    }

    private record EvidenceReadResult(List<WebSearchResult> results, int pagesRead, int verifiedPages) {
    }

    private record PageRead(boolean attempted, WebEvidence evidence) {
    }

    private record ScoredResult(WebSearchResult result, String domain, double score, int sourceOrder) {
    }
}
