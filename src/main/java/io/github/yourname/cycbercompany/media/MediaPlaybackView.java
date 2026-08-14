package io.github.yourname.cycbercompany.media;

import java.util.List;
import java.util.Map;

public record MediaPlaybackView(
        String status,
        String mediaId,
        String sourceId,
        String episodeId,
        String streamUrl,
        String playbackPageUrl,
        String mimeType,
        long durationMs,
        List<String> subtitleUrls,
        String message,
        Map<String, String> requestHeaders) {

    public MediaPlaybackView(String status, String mediaId, String sourceId, String episodeId,
                             String streamUrl, String mimeType, long durationMs,
                             List<String> subtitleUrls, String message) {
        this(status, mediaId, sourceId, episodeId, streamUrl, null, mimeType, durationMs, subtitleUrls, message, Map.of());
    }

    public MediaPlaybackView(String status, String mediaId, String sourceId, String episodeId,
                             String streamUrl, String playbackPageUrl, String mimeType, long durationMs,
                             List<String> subtitleUrls, String message) {
        this(status, mediaId, sourceId, episodeId, streamUrl, playbackPageUrl, mimeType, durationMs,
                subtitleUrls, message, Map.of());
    }
}
