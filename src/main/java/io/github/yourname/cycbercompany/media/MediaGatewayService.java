package io.github.yourname.cycbercompany.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Demo-owned media gateway. Provider URLs and provider headers never reach the browser. */
@Service
public class MediaGatewayService {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private static final int MAX_PLAYLIST_BYTES = 4 * 1024 * 1024;
    private static final Pattern HLS_URI = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");

    private final MediaPlaybackSelectionService selection;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, Target> targets = new ConcurrentHashMap<>();

    public MediaGatewayService(MediaPlaybackSelectionService selection) {
        this.selection = selection;
    }

    public MediaPlaybackView open(MediaResolveCommand command, String sourceUrl) {
        MediaPlaybackView resolved = selection.resolve(command, sourceUrl);
        if (resolved == null || !"READY".equals(resolved.status())
                || resolved.streamUrl() == null || resolved.streamUrl().isBlank()) {
            return resolved;
        }
        String token = issue(resolved.streamUrl(), resolved.requestHeaders());
        return new MediaPlaybackView(resolved.status(), resolved.mediaId(), resolved.sourceId(), resolved.episodeId(),
                "/api/v1/media/stream/" + token, resolved.playbackPageUrl(), resolved.mimeType(),
                resolved.durationMs(), resolved.subtitleUrls(), resolved.message(), Map.of());
    }

    public GatewayStream openStream(String token, String range) {
        Target target = targets.get(token);
        if (target == null || target.expiresAt().isBefore(Instant.now())) {
            targets.remove(token);
            return null;
        }
        cleanupExpired();
        try {
            URI uri = URI.create(target.url());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,video/*,*/*;q=0.2")
                    .header("User-Agent", target.headers().getOrDefault("User-Agent", "CycberCompany/VideoMod/1.0"))
                    .GET();
            if (range != null && !range.isBlank()) request.header("Range", range);
            target.headers().forEach((key, value) -> {
                if (isForwardableHeader(key) && !"User-Agent".equalsIgnoreCase(key)) request.header(key, value);
            });
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                return null;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (isPlaylist(target.url(), contentType)) {
                byte[] raw = readAtMost(response.body(), MAX_PLAYLIST_BYTES);
                response.body().close();
                String rewritten = rewritePlaylist(new String(raw, StandardCharsets.UTF_8), uri, target.headers());
                byte[] body = rewritten.getBytes(StandardCharsets.UTF_8);
                return GatewayStream.buffered(200, "application/vnd.apple.mpegurl", body);
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.headers().firstValue("Content-Range").ifPresent(value -> responseHeaders.put("Content-Range", value));
            response.headers().firstValue("Accept-Ranges").ifPresent(value -> responseHeaders.put("Accept-Ranges", value));
            return GatewayStream.streaming(response.statusCode(), contentType, contentLength, responseHeaders, response.body());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String issue(String url, Map<String, String> headers) {
        cleanupExpired();
        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> safeHeaders = new LinkedHashMap<>();
        if (headers != null) headers.forEach((key, value) -> {
            if (key != null && value != null && isForwardableHeader(key)) safeHeaders.put(key, value);
        });
        targets.put(token, new Target(url, Map.copyOf(safeHeaders), Instant.now().plus(TOKEN_TTL)));
        return token;
    }

    private String rewritePlaylist(String playlist, URI base, Map<String, String> headers) {
        StringBuilder output = new StringBuilder(playlist.length() + 256);
        for (String line : playlist.split("\\r?\\n", -1)) {
            String current = line;
            Matcher matcher = HLS_URI.matcher(current);
            StringBuffer attributes = new StringBuffer();
            while (matcher.find()) {
                String rewritten = gatewayUri(resolve(base, matcher.group(1)), headers);
                matcher.appendReplacement(attributes, Matcher.quoteReplacement("URI=\"" + rewritten + "\""));
            }
            matcher.appendTail(attributes);
            current = attributes.toString();
            if (!current.startsWith("#") && !current.isBlank() && !current.startsWith("data:")) {
                current = gatewayUri(resolve(base, current.trim()), headers);
            }
            output.append(current).append('\n');
        }
        return output.toString();
    }

    private String gatewayUri(URI uri, Map<String, String> headers) {
        if (uri == null || uri.getHost() == null) return uri == null ? "" : uri.toString();
        return "/api/v1/media/stream/" + issue(uri.toString(), headers);
    }

    private static URI resolve(URI base, String value) {
        try {
            URI candidate = URI.create(value.trim());
            return base.resolve(candidate);
        } catch (Exception ignored) {
            return base;
        }
    }

    private static boolean isPlaylist(String url, String contentType) {
        String lowerUrl = url.toLowerCase();
        String lowerType = contentType.toLowerCase();
        return lowerUrl.contains(".m3u8") || lowerType.contains("mpegurl") || lowerType.contains("vnd.apple.mpegurl");
    }

    private static byte[] readAtMost(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > maxBytes) throw new IOException("Playlist is too large.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        targets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static boolean isForwardableHeader(String key) {
        return "Referer".equalsIgnoreCase(key) || "Origin".equalsIgnoreCase(key)
                || "User-Agent".equalsIgnoreCase(key) || "Cookie".equalsIgnoreCase(key)
                || "Authorization".equalsIgnoreCase(key);
    }

    private record Target(String url, Map<String, String> headers, Instant expiresAt) {
    }

    public static final class GatewayStream {
        private final int status;
        private final String contentType;
        private final long contentLength;
        private final InputStream stream;
        private final byte[] body;
        private final Map<String, String> responseHeaders;

        private GatewayStream(int status, String contentType, long contentLength, Map<String, String> responseHeaders,
                              InputStream stream, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.stream = stream;
            this.body = body;
            this.responseHeaders = responseHeaders;
        }

        static GatewayStream streaming(int status, String contentType, long contentLength, Map<String, String> responseHeaders,
                                       InputStream stream) {
            return new GatewayStream(status, contentType, contentLength, Map.copyOf(responseHeaders), stream, null);
        }

        static GatewayStream buffered(int status, String contentType, byte[] body) {
            return new GatewayStream(status, contentType, body.length, Map.of(), null, body);
        }

        public int status() { return status; }
        public String contentType() { return contentType; }
        public long contentLength() { return contentLength; }
        public InputStream stream() { return stream; }
        public byte[] body() { return body; }
        public Map<String, String> responseHeaders() { return responseHeaders; }
    }
}
