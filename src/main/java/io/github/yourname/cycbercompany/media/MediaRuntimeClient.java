package io.github.yourname.cycbercompany.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Client for the isolated TVBox compatibility worker. The worker owns all DEX/JNI/JS
 * execution; the Spring process only exchanges normalized JSON.
 */
@Service
public class MediaRuntimeClient {
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String token;
    private final MediaRuntimeProcessService processService;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Autowired
    public MediaRuntimeClient(ObjectMapper mapper,
                              @Value("${app.demos.video.runtime-endpoint:}") String endpoint,
                              @Value("${app.demos.video.runtime-token:}") String token,
                              MediaRuntimeProcessService processService) {
        this.mapper = mapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.token = token == null ? "" : token.trim();
        this.processService = processService;
    }

    /** Compatibility constructor for focused runtime contract tests. */
    public MediaRuntimeClient(ObjectMapper mapper, String endpoint) {
        this(mapper, endpoint, "", null);
    }

    MediaRuntimeClient(ObjectMapper mapper, String endpoint, String token) {
        this(mapper, endpoint, token, null);
    }

    public MediaSearchView search(String query, MediaCatalogView catalog) {
        return search(query, catalog, null);
    }

    public MediaSearchView search(String query, MediaCatalogView catalog, String sourceId) {
        if (processService != null) processService.ensureStarted();
        if (endpoint.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Runtime endpoint must use HTTP(S).");
            }
            List<String> sourceKeys = catalog.sources().stream()
                    .filter(source -> sourceId == null || sourceId.isBlank() || source.key().equals(sourceId))
                    .map(MediaSourceView::key)
                    .toList();
            Map<String, Object> requestBody = Map.of(
                    "operation", "media.search",
                    "query", query,
                    "sourceUrl", catalog.sourceUrl(),
                    "configDigest", catalog.configDigest(),
                    "sourceKeys", sourceKeys);
            byte[] body = mapper.writeValueAsBytes(requestBody);
            HttpResponse<byte[]> response = client.send(request(uri, body, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return unavailable(query, "Runtime returned HTTP " + response.statusCode(), catalog);
            }
            MediaSearchView parsed = mapper.readValue(response.body(), MediaSearchView.class);
            return parsed == null ? unavailable(query, "Runtime returned an empty response.", catalog) : parsed;
        } catch (Exception ex) {
            return unavailable(query, message(ex), catalog);
        }
    }

    public MediaPlaybackView resolvePlayback(MediaResolveCommand command, MediaCatalogView catalog) {
        return resolvePlayback(command, catalog, Duration.ofSeconds(45));
    }

    MediaPlaybackView probePlayback(MediaResolveCommand command, MediaCatalogView catalog, Duration timeout) {
        return resolvePlayback(command, catalog, timeout);
    }

    private MediaPlaybackView resolvePlayback(MediaResolveCommand command, MediaCatalogView catalog, Duration timeout) {
        if (processService != null) processService.ensureStarted();
        if (endpoint.isBlank()) {
            return new MediaPlaybackView("RUNTIME_REQUIRED", command.mediaId(), command.sourceId(),
                    command.episodeId(), null, null, 0, List.of(),
                    "Isolated media provider runtime is not configured.");
        }
        try {
            URI uri = URI.create(endpoint);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Runtime endpoint must use HTTP(S).");
            }
            Map<String, Object> requestBody = Map.of(
                    "operation", "media.resolvePlayback",
                    "mediaId", command.mediaId(),
                    "sourceId", command.sourceId() == null ? "" : command.sourceId(),
                    "episodeId", command.episodeId() == null ? "" : command.episodeId(),
                    "sourceUrl", catalog.sourceUrl(),
                    "configDigest", catalog.configDigest());
            HttpResponse<byte[]> response = client.send(request(uri, mapper.writeValueAsBytes(requestBody), timeout), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return unavailablePlayback(command, "Runtime returned HTTP " + response.statusCode());
            }
            MediaPlaybackView value = mapper.readValue(response.body(), MediaPlaybackView.class);
            return value == null ? unavailablePlayback(command, "Runtime returned an empty response.") : normalizePlayback(value, command);
        } catch (Exception ex) {
            return unavailablePlayback(command, message(ex));
        }
    }

    public MediaRuntimeStatusView status() {
        if (processService != null) processService.ensureStarted();
        if (endpoint.isBlank()) {
            return new MediaRuntimeStatusView("NOT_CONFIGURED", "Isolated media provider runtime is not configured.");
        }
        try {
            URI endpointUri = URI.create(endpoint);
            if (!"http".equalsIgnoreCase(endpointUri.getScheme()) && !"https".equalsIgnoreCase(endpointUri.getScheme())) {
                return new MediaRuntimeStatusView("UNAVAILABLE", "Runtime endpoint must use HTTP(S).");
            }
            URI healthUri = new URI(endpointUri.getScheme(), endpointUri.getAuthority(), "/health", null, null);
            HttpRequest.Builder builder = HttpRequest.newBuilder(healthUri)
                    .timeout(Duration.ofSeconds(2))
                    .header("Accept", "application/json")
                    .GET();
            if (!token.isBlank()) builder.header("X-Media-Runtime-Token", token);
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return new MediaRuntimeStatusView("CONFIGURED", "Isolated media provider runtime is configured.");
            }
            if (response.statusCode() / 100 != 2) {
                return new MediaRuntimeStatusView("UNAVAILABLE", "Runtime health check returned HTTP " + response.statusCode() + ".");
            }
            Map<?, ?> health = mapper.readValue(response.body(), Map.class);
            boolean adapterConfigured = Boolean.TRUE.equals(health.get("adapterConfigured"));
            boolean adapterReady = Boolean.TRUE.equals(health.get("adapterReady"));
            String adapterMessage = health.get("adapterMessage") == null ? "" : health.get("adapterMessage").toString();
            return adapterConfigured && adapterReady
                    ? new MediaRuntimeStatusView("READY", "Isolated media provider runtime and adapter are ready.")
                    : new MediaRuntimeStatusView("ADAPTER_REQUIRED", adapterMessage.isBlank()
                    ? "Runtime is online, but no approved ready media adapter is configured."
                    : adapterMessage);
        } catch (Exception ex) {
            return new MediaRuntimeStatusView("UNAVAILABLE", "Runtime health check failed: " + message(ex));
        }
    }

    private static MediaSearchView unavailable(String query, String message, MediaCatalogView catalog) {
        return new MediaSearchView(query, "RUNTIME_UNAVAILABLE", message,
                List.of(), catalog.sources().stream().filter(MediaSourceView::searchable).map(MediaSourceView::key).toList());
    }

    private HttpRequest request(URI uri, byte[] body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!token.isBlank()) {
            builder.header("X-Media-Runtime-Token", token);
        }
        return builder.build();
    }

    private static MediaPlaybackView unavailablePlayback(MediaResolveCommand command, String message) {
        return new MediaPlaybackView("RUNTIME_UNAVAILABLE", command.mediaId(), command.sourceId(),
                command.episodeId(), null, null, 0, List.of(), message);
    }

    /**
     * Keep an untrusted provider from handing browser-facing file, data, or custom-scheme
     * URLs to the Mod UI. Stream delivery remains the provider's responsibility, but its
     * result must use the narrow transport contract declared by this platform.
     */
    private static MediaPlaybackView normalizePlayback(MediaPlaybackView value, MediaResolveCommand command) {
        String streamUrl = value.streamUrl();
        if (streamUrl != null && !streamUrl.isBlank() && !isHttpUrl(streamUrl)) {
            return unavailablePlayback(command, "Runtime returned a stream URL with an unsupported scheme.");
        }
        String playbackPageUrl = value.playbackPageUrl();
        if (playbackPageUrl != null && !playbackPageUrl.isBlank() && !isHttpUrl(playbackPageUrl)) {
            return unavailablePlayback(command, "Runtime returned a playback page URL with an unsupported scheme.");
        }
        List<String> subtitles = value.subtitleUrls() == null ? List.of() : value.subtitleUrls().stream()
                .filter(Objects::nonNull)
                .filter(MediaRuntimeClient::isHttpUrl)
                .toList();
        Map<String, String> headers = new LinkedHashMap<>();
        if (value.requestHeaders() != null) {
            value.requestHeaders().forEach((key, headerValue) -> {
                if (key != null && headerValue != null && isForwardableHeader(key)) {
                    headers.put(key, headerValue);
                }
            });
        }
        return new MediaPlaybackView(
                value.status() == null || value.status().isBlank() ? "UNAVAILABLE" : value.status(),
                value.mediaId() == null || value.mediaId().isBlank() ? command.mediaId() : value.mediaId(),
                value.sourceId() == null ? command.sourceId() : value.sourceId(),
                value.episodeId() == null ? command.episodeId() : value.episodeId(),
                streamUrl,
                playbackPageUrl,
                value.mimeType(),
                Math.max(0, value.durationMs()),
                subtitles,
                value.message() == null ? "" : value.message(),
                Map.copyOf(headers));
    }

    private static boolean isForwardableHeader(String key) {
        return "referer".equalsIgnoreCase(key) || "origin".equalsIgnoreCase(key)
                || "user-agent".equalsIgnoreCase(key) || "cookie".equalsIgnoreCase(key)
                || "authorization".equalsIgnoreCase(key);
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
