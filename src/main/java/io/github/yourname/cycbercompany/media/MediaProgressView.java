package io.github.yourname.cycbercompany.media;

import java.time.Instant;

public record MediaProgressView(
        String modId,
        String mediaId,
        String sourceId,
        String episodeId,
        long positionMs,
        long durationMs,
        boolean completed,
        Instant updatedAt) {
}
