package io.github.yourname.cycbercompany.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Imports the public TVBox-style image/base64 JSON envelope, but deliberately does
 * not load remote DEX/JNI/JS spiders. Those require a separately reviewed runtime.
 */
@Service
public class TvBoxConfigService {
    public static final String DEFAULT_SOURCE_URL = "http://fty.xxooo.cf/tv";
    private static final Duration CATALOG_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration WEBSITE_SEARCH_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration WEBSITE_SEARCH_EMPTY_CACHE_TTL = Duration.ofSeconds(15);
    private static final Duration WEBSITE_SEARCH_STALE_TTL = Duration.ofHours(1);
    private static final Duration WEBSITE_SEARCH_TIMEOUT = Duration.ofSeconds(7);
    private final ObjectMapper mapper;
    private final MediaRuntimeClient runtime;
    private final boolean allowPrivateHosts;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // The configured TVBox CDN returns a false redirect when negotiated as HTTP/2.
            // Its JPEG/Base64 envelope is served correctly over HTTP/1.1.
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, CachedWebsiteSearch> websiteSearchCache = new ConcurrentHashMap<>();
    private final AtomicReference<CachedCatalog> cached = new AtomicReference<>();

    @Autowired
    public TvBoxConfigService(ObjectMapper mapper, MediaRuntimeClient runtime) {
        this.mapper = mapper;
        this.runtime = runtime;
        this.allowPrivateHosts = false;
    }

    /** Compatibility constructor for isolated unit tests. */
    public TvBoxConfigService(ObjectMapper mapper) {
        this(mapper, null, false);
    }

    TvBoxConfigService(ObjectMapper mapper, boolean allowPrivateHosts) {
        this(mapper, null, allowPrivateHosts);
    }

    private TvBoxConfigService(ObjectMapper mapper, MediaRuntimeClient runtime, boolean allowPrivateHosts) {
        this.mapper = mapper;
        this.runtime = runtime;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public MediaCatalogView catalog(String sourceUrl) {
        String url = sourceUrl == null || sourceUrl.isBlank() ? DEFAULT_SOURCE_URL : sourceUrl.trim();
        CachedCatalog previous = cached.get();
        if (previous != null && previous.url().equals(url)
                && previous.fetchedAt().plus(CATALOG_CACHE_TTL).isAfter(Instant.now())) {
            return previous.catalog();
        }
        try {
            byte[] body = fetch(url, 2_000_000);
            JsonNode config = decodeConfig(body);
            String digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            List<MediaSourceView> sources = new ArrayList<>();
            JsonNode sites = config.path("sites");
            if (sites.isArray()) {
                for (JsonNode site : sites) {
                    String api = site.path("api").asText("");
                    boolean isolated = api.startsWith("csp_") || api.startsWith("http") && api.contains(".js");
                    String websiteUrl = websiteUrl(site, api);
                    boolean cloudIndex = isCloudIndex(site, api);
                    boolean downloadOnly = isDownloadOnly(site, websiteUrl);
                    boolean searchable = site.path("searchable").asInt(0) == 1 && !cloudIndex && !downloadOnly;
                    sources.add(new MediaSourceView(
                            site.path("key").asText("unknown"),
                            site.path("name").asText(site.path("key").asText("unknown")),
                            isolated ? (api.startsWith("csp_") ? "tvbox-spider" : "tvbox-js-spider") : "unknown",
                            searchable,
                            site.path("quickSearch").asInt(0) == 1,
                            isolated,
                            sourceStatus(cloudIndex, downloadOnly, websiteUrl, isolated),
                            websiteUrl,
                            sourceAccessMode(site, api, websiteUrl)));
                }
            }
            List<String> lives = new ArrayList<>();
            if (config.path("lives").isArray()) config.path("lives").forEach(item -> lives.add(item.path("name").asText("live")));
            List<String> warnings = new ArrayList<>();
            if (config.has("spider")) warnings.add("配置包含远程 Spider；平台不会在后端主进程执行 DEX/JNI/JS 代码。");
            warnings.add("资源来源和播放权利由用户及源提供方负责；平台只保存标准化元数据。");
            MediaCatalogView result = new MediaCatalogView(url, digest, Instant.now(), "CONFIG_IMPORTED",
                    List.copyOf(sources), List.copyOf(lives), List.copyOf(warnings));
            cached.set(new CachedCatalog(url, result.fetchedAt(), result));
            return result;
        } catch (Exception ex) {
            return new MediaCatalogView(url, null, Instant.now(), "UNAVAILABLE", List.of(), List.of(), List.of(safeMessage(ex)));
        }
    }

    public MediaSearchView search(String query, String sourceUrl) {
        return search(query, sourceUrl, null);
    }

    public MediaSearchView search(String query, String sourceUrl, String sourceId) {
        MediaCatalogView catalog = catalog(sourceUrl);
        List<String> searchable = catalog.sources().stream()
                .filter(MediaSourceView::searchable)
                .filter(source -> sourceId == null || sourceId.isBlank() || source.key().equals(sourceId))
                .map(MediaSourceView::key).toList();
        if (!"CONFIG_IMPORTED".equals(catalog.runtimeStatus())) {
            return new MediaSearchView(query, "SOURCE_UNAVAILABLE", "Unable to import the configured media sources.",
                    List.of(), searchable);
        }

        MediaSearchView websiteResult = searchWebsites(query, catalog, sourceId);
        MediaSearchView runtimeResult = runtime == null ? null : runtime.search(query, catalog, sourceId);
        Map<String, MediaItemView> merged = new LinkedHashMap<>();
        websiteResult.items().forEach(item -> merged.put(item.id(), item));
        if (runtimeResult != null && runtimeResult.items() != null) {
            runtimeResult.items().stream()
                    .filter(item -> sourceId == null || sourceId.isBlank() || sourceId.equals(item.sourceKey()))
                    .forEach(item -> merged.putIfAbsent(item.id(), item));
        }
        List<MediaItemView> ordered = merged.values().stream()
                .sorted(Comparator.comparingInt(TvBoxConfigService::itemPriority))
                .toList();
        if (!ordered.isEmpty()) {
            return new MediaSearchView(query, "READY", "", ordered, searchable);
        }
        if (runtimeResult != null && !"READY".equals(runtimeResult.status())) {
            return new MediaSearchView(query, runtimeResult.status(), runtimeResult.message(), List.of(), searchable);
        }
        return new MediaSearchView(query, "RUNTIME_REQUIRED",
                "No approved public adapter returned a result; an isolated provider adapter is required.",
                List.of(), searchable);
    }

    private MediaSearchView searchLegacy(String query, String sourceUrl) {
        MediaCatalogView catalog = catalog(sourceUrl);
        List<String> searchable = catalog.sources().stream().filter(MediaSourceView::searchable).map(MediaSourceView::key).toList();
        if (!"CONFIG_IMPORTED".equals(catalog.runtimeStatus())) {
            return new MediaSearchView(query, "SOURCE_UNAVAILABLE", "无法读取影视仓配置。", List.of(), searchable);
        }
        MediaSearchView websiteResult = searchWebsites(query, catalog, null);
        if (!websiteResult.items().isEmpty()) {
            return websiteResult;
        }
        MediaSearchView runtimeResult = runtime == null ? null : runtime.search(query, catalog);
        if (runtimeResult != null) {
            return runtimeResult;
        }
        return new MediaSearchView(query, "RUNTIME_REQUIRED",
                "已读取影视仓源目录，但 csp_* 搜索需要隔离兼容运行时；当前版本不会执行远程 Spider。",
                List.of(), searchable);
    }

    public MediaPlaybackView resolvePlayback(MediaResolveCommand command, String sourceUrl) {
        MediaCatalogView catalog = catalog(sourceUrl);
        if (!"CONFIG_IMPORTED".equals(catalog.runtimeStatus())) {
            return new MediaPlaybackView("SOURCE_UNAVAILABLE", command.mediaId(), command.sourceId(),
                    command.episodeId(), null, null, 0, List.of(), "Unable to import media source configuration.");
        }
        MediaPlaybackView websitePlayback = resolveWebsitePlayback(command, catalog);
        if (websitePlayback != null) return websitePlayback;
        MediaPlaybackView result = runtime == null ? null : runtime.resolvePlayback(command, catalog);
        return result == null
                ? new MediaPlaybackView("RUNTIME_REQUIRED", command.mediaId(), command.sourceId(), command.episodeId(),
                        null, null, 0, List.of(), "Isolated media provider runtime is not configured.")
                : result;
    }

    MediaPlaybackView probePlayback(MediaResolveCommand command, String sourceUrl, Duration timeout) {
        MediaCatalogView catalog = catalog(sourceUrl);
        if (!"CONFIG_IMPORTED".equals(catalog.runtimeStatus())) {
            return new MediaPlaybackView("SOURCE_UNAVAILABLE", command.mediaId(), command.sourceId(),
                    command.episodeId(), null, null, 0, List.of(), "Unable to import media source configuration.");
        }
        MediaPlaybackView websitePlayback = resolveWebsitePlayback(command, catalog);
        if (websitePlayback != null) return websitePlayback;
        MediaPlaybackView result = runtime == null ? null : runtime.probePlayback(command, catalog, timeout);
        return result == null
                ? new MediaPlaybackView("RUNTIME_REQUIRED", command.mediaId(), command.sourceId(), command.episodeId(),
                        null, null, 0, List.of(), "Isolated media provider runtime is not configured.")
                : result;
    }

    private byte[] fetch(String url, int maxBytes) throws IOException, InterruptedException {
        return fetch(url, maxBytes, Duration.ofSeconds(20));
    }

    private byte[] fetch(String url, int maxBytes, Duration timeout) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("Only HTTP(S) sources are supported.");
        if (!allowPrivateHosts && isPrivateHost(uri.getHost())) {
            throw new IOException("Private source hosts are not allowed.");
        }
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .header("User-Agent", "CycberCompany/VideoMod/1.0")
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw new IOException("Source returned HTTP " + response.statusCode());
        if (response.body().length > maxBytes) throw new IOException("Source response is too large.");
        return response.body();
    }

    /**
     * A conservative first website adapter. It only uses URLs explicitly present
     * in the imported config and never guesses endpoints from csp_* class names.
     */
    private MediaSearchView searchWebsites(String query, MediaCatalogView catalog, String sourceId) {
        List<MediaItemView> items = new ArrayList<>();
        List<MediaSourceView> orderedSources = catalog.sources().stream()
                .filter(MediaSourceView::searchable)
                .filter(source -> sourceId == null || sourceId.isBlank() || source.key().equals(sourceId))
                .sorted(Comparator.comparingInt(TvBoxConfigService::accessPriority))
                .toList();
        List<CompletableFuture<List<MediaItemView>>> lookups = orderedSources.stream()
                .filter(source -> source.websiteUrl() != null)
                .map(source -> CompletableFuture.supplyAsync(() -> searchWebsiteSource(query, source)))
                .toList();
        for (CompletableFuture<List<MediaItemView>> lookup : lookups) {
            for (MediaItemView item : lookup.join()) {
                if (items.size() >= 48) break;
                items.add(item);
            }
            if (items.size() >= 48) break;
        }
        List<String> keys = catalog.sources().stream().filter(MediaSourceView::searchable).map(MediaSourceView::key).toList();
        return new MediaSearchView(query, items.isEmpty() ? "NO_WEBSITE_RESULTS" : "READY", "", List.copyOf(items), keys);
    }

    private List<MediaItemView> searchWebsiteSource(String query, MediaSourceView source) {
        String cacheKey = query + "\u0000" + source.key();
        CachedWebsiteSearch cachedResult = websiteSearchCache.get(cacheKey);
        Instant now = Instant.now();
        if (cachedResult != null) {
            Duration cacheTtl = cachedResult.items().isEmpty()
                    ? WEBSITE_SEARCH_EMPTY_CACHE_TTL : WEBSITE_SEARCH_CACHE_TTL;
            if (cachedResult.fetchedAt().plus(cacheTtl).isAfter(now)) {
                return cachedResult.items();
            }
        }
        try {
            List<MediaItemView> fresh = fetchWebsiteSource(query, source);
            if (!fresh.isEmpty()) {
                List<MediaItemView> snapshot = List.copyOf(fresh);
                websiteSearchCache.put(cacheKey, new CachedWebsiteSearch(now, snapshot));
                return snapshot;
            }
        } catch (Exception ignored) {
            // Optional source adapters must not hide results from other adapters.
        }
        if (cachedResult != null && !cachedResult.items().isEmpty()
                && cachedResult.fetchedAt().plus(WEBSITE_SEARCH_STALE_TTL).isAfter(now)) {
            websiteSearchCache.put(cacheKey, new CachedWebsiteSearch(now, cachedResult.items()));
            return cachedResult.items();
        }
        websiteSearchCache.put(cacheKey, new CachedWebsiteSearch(now, List.of()));
        return List.of();
    }

    private List<MediaItemView> fetchWebsiteSource(String query, MediaSourceView source) throws IOException, InterruptedException {
        URI base = URI.create(source.websiteUrl());
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase();
        if (!host.endsWith("libvio.pw")) return List.of();
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        URI search = base.resolve("/search/" + encoded + ".html");
        Document document = Jsoup.parse(new String(fetch(search.toString(), 1_500_000, WEBSITE_SEARCH_TIMEOUT), StandardCharsets.UTF_8), search.toString());
        List<MediaItemView> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href*=/detail/]") ) {
            String href = link.attr("href");
            String title = link.attr("title").trim();
            if (title.isBlank()) title = link.text().trim();
            if (title.isBlank() || !seen.add(href)) continue;
            String poster = link.hasAttr("data-original") ? link.absUrl("data-original") : null;
            String detailUrl = base.resolve(href).toString();
            items.add(new MediaItemView("website:" + source.key() + ":" + detailUrl, title, "video",
                    source.key(), source.name(), poster, false, "WEBSITE_PAGE", "ANONYMOUS", null));
            if (items.size() >= 12) break;
        }
        return items;
    }

    private static String sourceAccessMode(JsonNode site, String api, String websiteUrl) {
        if (isCloudIndex(site, api)) return "LOGIN_MAY_BE_REQUIRED";
        return websiteUrl == null ? "UNKNOWN" : "ANONYMOUS";
    }

    private static String sourceStatus(boolean cloudIndex, boolean downloadOnly, String websiteUrl, boolean isolated) {
        if (cloudIndex) return "CLOUD_INDEX";
        if (downloadOnly) return "DOWNLOAD_ONLY";
        return websiteUrl != null ? "WEBSITE_DISCOVERED" : isolated ? "ISOLATED_RUNTIME_REQUIRED" : "UNSUPPORTED";
    }

    private static boolean isCloudIndex(JsonNode site, String api) {
        String name = site.path("name").asText("");
        return site.path("ext").isObject() && site.path("ext").has("Cloud-drive")
                || api.contains("zps") || name.contains("云盘") || name.contains("盘搜") || name.contains("四盘");
    }

    private static boolean isDownloadOnly(JsonNode site, String websiteUrl) {
        String name = site.path("name").asText("");
        if (name.contains("磁力") || name.contains("下载")) return true;
        try {
            String host = websiteUrl == null ? "" : URI.create(websiteUrl).getHost();
            return host != null && host.equalsIgnoreCase("www.xb6v.com");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static int accessPriority(MediaSourceView source) {
        return switch (source.accessMode()) {
            case "ANONYMOUS" -> 0;
            case "LOGIN_MAY_BE_REQUIRED" -> 2;
            default -> 1;
        };
    }

    private static int itemPriority(MediaItemView item) {
        if (item.playable() || "READY".equals(item.availability())) return 0;
        if ("ANONYMOUS".equals(item.accessMode())) return 1;
        if ("LOGIN_REQUIRED".equals(item.accessMode())) return 3;
        return 2;
    }

    private MediaPlaybackView resolveWebsitePlayback(MediaResolveCommand command, MediaCatalogView catalog) {
        if (command.mediaId() == null || !command.mediaId().startsWith("website:")) return null;
        int separator = command.mediaId().indexOf(':', "website:".length());
        if (separator < 0 || separator + 1 >= command.mediaId().length()) return null;
        String detailUrl = command.mediaId().substring(separator + 1);
        try {
            URI detail = URI.create(detailUrl);
            MediaSourceView source = catalog.sources().stream()
                    .filter(candidate -> candidate.key().equals(command.sourceId()))
                    .filter(candidate -> candidate.websiteUrl() != null)
                    .filter(candidate -> sameHost(URI.create(candidate.websiteUrl()), detail))
                    .findFirst().orElse(null);
            if (source == null) return null;
            if (!detail.getPath().startsWith("/detail/")) return null;
            Document document = Jsoup.parse(new String(fetch(detailUrl, 1_500_000), StandardCharsets.UTF_8), detailUrl);
            Element play = document.select("a[href^=/w/]").first();
            if (play == null) {
                return new MediaPlaybackView("WEBSITE_PAGE", command.mediaId(), command.sourceId(), command.episodeId(),
                        null, null, 0, List.of(), "该站点没有公开播放入口。");
            }
            String pageUrl = detail.resolve(play.attr("href")).toString();
            Document playDocument = Jsoup.parse(new String(fetch(pageUrl, 1_500_000), StandardCharsets.UTF_8), pageUrl);
            String direct = playDocument.select("source[src$=.m3u8], source[src$=.mp4], video[src$=.m3u8], video[src$=.mp4]")
                    .stream().map(element -> element.absUrl("src")).filter(value -> !value.isBlank()).findFirst().orElse(null);
            return new MediaPlaybackView(direct == null ? "WEBSITE_PAGE" : "READY", command.mediaId(), command.sourceId(),
                    command.episodeId(), direct, direct == null ? pageUrl : null, direct == null ? null : "application/octet-stream", 0, List.of(),
                    direct == null ? "站点已提供公开播放页：" + pageUrl : "网站页面已解析出媒体地址。");
        } catch (Exception ignored) {
            return new MediaPlaybackView("WEBSITE_UNAVAILABLE", command.mediaId(), command.sourceId(), command.episodeId(),
                    null, null, 0, List.of(), "网站详情页暂时无法访问。");
        }
    }

    private static String websiteUrl(JsonNode site, String api) {
        String fromExt = site.path("ext").path("siteUrl").asText("").trim();
        if (fromExt.startsWith("http://") || fromExt.startsWith("https://")) return fromExt;
        if ((api.startsWith("http://") || api.startsWith("https://")) && !api.contains(".js")) return api;
        String ext = site.path("ext").asText("").trim();
        return (ext.startsWith("http://") || ext.startsWith("https://")) && !ext.contains(".js") ? ext : null;
    }

    private static boolean sameHost(URI left, URI right) {
        return left.getHost() != null && right.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost());
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isPrivateHost(String host) {
        if (host == null || host.isBlank() || host.equalsIgnoreCase("localhost") || host.equals("::1")) {
            return true;
        }
        String normalized = host.toLowerCase();
        if (normalized.startsWith("127.") || normalized.startsWith("10.") || normalized.startsWith("192.168.")) {
            return true;
        }
        if (normalized.startsWith("169.254.")) {
            return true;
        }
        if (normalized.startsWith("172.")) {
            String[] parts = normalized.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode decodeConfig(byte[] body) throws IOException {
        String text = new String(body, StandardCharsets.ISO_8859_1);
        int marker = text.lastIndexOf("**");
        if (marker < 0) throw new IOException("TVBox envelope marker not found.");
        String encoded = text.substring(marker + 2).trim();
        byte[] decoded = Base64.getDecoder().decode(encoded);
        JsonNode json = mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
        if (!json.isObject() || !json.has("sites")) throw new IOException("TVBox config has no sites.");
        return json;
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record CachedCatalog(String url, Instant fetchedAt, MediaCatalogView catalog) {
    }

    private record CachedWebsiteSearch(Instant fetchedAt, List<MediaItemView> items) {
    }
}
