package io.github.yourname.agentstudio.media;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Resolves one candidate per source and verifies that the returned URL behaves
 * like a media stream before the UI treats it as directly playable.
 */
@Service
public class MediaPlaybackSelectionService {
    // A catalogue can contain dozens of sources. Six probes meant that a
    // healthy source later in a TVBox configuration was never considered.
    // Keep the fan-out bounded, but cover the normal full set of searchable
    // sources before asking the user to select one manually.
    private static final int MAX_SOURCES_TO_PROBE = 32;
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STREAM_PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PLAYBACK_CACHE_TTL = Duration.ofSeconds(90);

    private final TvBoxConfigService sources;
    private final HttpClient probeClient = HttpClient.newBuilder()
            .connectTimeout(STREAM_PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, CachedPlayback> playbackCache = new ConcurrentHashMap<>();
    private final Map<String, SourceHealth> sourceHealth = new ConcurrentHashMap<>();

    public MediaPlaybackSelectionService(TvBoxConfigService sources) {
        this.sources = sources;
    }

    public MediaSearchView search(String query, String sourceUrl, String sourceId) {
        MediaSearchView result = sources.search(query, sourceUrl, sourceId);
        if (result.items() == null || result.items().isEmpty()) return result;

        Map<String, MediaItemView> firstBySource = new LinkedHashMap<>();
        result.items().forEach(item -> firstBySource.putIfAbsent(item.sourceKey(), item));
        List<MediaItemView> candidates = firstBySource.values().stream()
                .sorted(Comparator
                        .comparingInt(MediaPlaybackSelectionService::accessRank)
                        .thenComparingInt(item -> sourceRank(item.sourceKey())))
                .limit(MAX_SOURCES_TO_PROBE)
                .toList();

        List<CompletableFuture<ProbeResult>> probes = candidates.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> probe(item, sourceUrl))
                        .completeOnTimeout(ProbeResult.failed(item), 8, TimeUnit.SECONDS))
                .toList();
        Map<String, ProbeResult> probed = new LinkedHashMap<>();
        probes.stream().map(CompletableFuture::join).forEach(probe -> probed.put(probe.item().id(), probe));

        List<MediaItemView> ordered = new ArrayList<>(result.items().size());
        for (MediaItemView item : result.items()) {
            ProbeResult probe = probed.get(item.id());
            boolean verified = probe != null && probe.playable();
            ordered.add(new MediaItemView(item.id(), item.title(), item.type(), item.sourceKey(), item.sourceName(),
                    item.posterUrl(), verified, verified ? "READY" : "UNVERIFIED",
                    item.accessMode(), item.authProvider()));
        }
        ordered.sort(Comparator
                .comparingInt((MediaItemView item) -> item.playable() ? 0 : 1)
                .thenComparingInt(item -> sourceRank(item.sourceKey()))
                .thenComparingInt(MediaPlaybackSelectionService::accessRank));
        return new MediaSearchView(result.query(), result.status(), result.message(), List.copyOf(ordered), result.sourceKeys());
    }

    public MediaPlaybackView resolve(MediaResolveCommand command, String sourceUrl) {
        String key = playbackKey(command);
        CachedPlayback cached = playbackCache.get(key);
        if (cached != null && cached.fetchedAt().plus(PLAYBACK_CACHE_TTL).isAfter(Instant.now())) {
            return cached.playback();
        }
        return sources.resolvePlayback(command, sourceUrl);
    }

    private ProbeResult probe(MediaItemView item, String sourceUrl) {
        long started = System.nanoTime();
        MediaResolveCommand command = new MediaResolveCommand(item.id(), item.sourceKey(), "1");
        try {
            MediaPlaybackView playback = sources.probePlayback(command, sourceUrl, RESOLVE_TIMEOUT);
            boolean playable = playback != null && "READY".equals(playback.status())
                    && playback.streamUrl() != null && probeStream(playback.streamUrl());
            long latency = elapsedMillis(started);
            updateHealth(item.sourceKey(), playable, latency);
            if (playable) {
                playbackCache.put(playbackKey(command), new CachedPlayback(Instant.now(), playback));
                return new ProbeResult(item, true);
            }
        } catch (Exception ignored) {
            updateHealth(item.sourceKey(), false, elapsedMillis(started));
        }
        return ProbeResult.failed(item);
    }

    private boolean probeStream(String streamUrl) {
        try {
            URI uri = URI.create(streamUrl);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return false;
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(STREAM_PROBE_TIMEOUT)
                    .header("Range", "bytes=0-2047")
                    .header("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,video/*,*/*;q=0.2")
                    .header("User-Agent", "AgentStudio/VideoMod/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = probeClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ignored = response.body()) {
                if (response.statusCode() != 200 && response.statusCode() != 206) return false;
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
                return contentType.startsWith("video/")
                        || contentType.contains("mpegurl")
                        || contentType.contains("octet-stream")
                        || path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".m4v")
                        || path.endsWith(".webm") || path.endsWith(".ts");
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateHealth(String sourceId, boolean success, long latencyMs) {
        if (sourceId == null || sourceId.isBlank()) return;
        sourceHealth.compute(sourceId, (ignored, previous) -> {
            SourceHealth old = previous == null ? new SourceHealth(0, 0, 0, 0) : previous;
            long average = old.averageLatencyMs() == 0 ? latencyMs : (old.averageLatencyMs() * 3 + latencyMs) / 4;
            return success
                    ? new SourceHealth(old.successes() + 1, old.failures(), 0, average)
                    : new SourceHealth(old.successes(), old.failures() + 1, old.consecutiveFailures() + 1, average);
        });
    }

    private int sourceRank(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return 15_000;
        SourceHealth health = sourceHealth.get(sourceId);
        if (health == null) return 10_000;
        int reliabilityPenalty = health.consecutiveFailures() >= 3 ? 20_000 : health.failures() * 200;
        int successBonus = Math.min(8_000, health.successes() * 1_000);
        return 10_000 + reliabilityPenalty - successBonus + (int) Math.min(5_000, health.averageLatencyMs());
    }

    private static int accessRank(MediaItemView item) {
        if ("ANONYMOUS".equals(item.accessMode())) return 0;
        if ("LOGIN_REQUIRED".equals(item.accessMode())) return 2;
        return 1;
    }

    private static String playbackKey(MediaResolveCommand command) {
        return String.valueOf(command.mediaId()) + '\u0000' + String.valueOf(command.sourceId())
                + '\u0000' + String.valueOf(command.episodeId());
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private record ProbeResult(MediaItemView item, boolean playable) {
        static ProbeResult failed(MediaItemView item) {
            return new ProbeResult(item, false);
        }
    }

    private record CachedPlayback(Instant fetchedAt, MediaPlaybackView playback) {
    }

    private record SourceHealth(int successes, int failures, int consecutiveFailures, long averageLatencyMs) {
    }
}
