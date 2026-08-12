package io.github.yourname.agentstudio.media;

import java.time.Instant;
import java.util.List;

public record MediaCatalogView(
        String sourceUrl,
        String configDigest,
        Instant fetchedAt,
        String runtimeStatus,
        List<MediaSourceView> sources,
        List<String> liveGroups,
        List<String> warnings) {
}
