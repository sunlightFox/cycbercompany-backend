package io.github.yourname.agentstudio.skill;

import java.time.Instant;

/**
 * Compact skill card used by list pages and agent configuration screens.
 */
public record SkillView(
        String id,
        String name,
        String description,
        boolean enabled,
        Instant installedAt,
        String sourceRepository,
        String sourceUrl,
        String ref,
        String path,
        int fileCount,
        long sizeBytes) {
}
