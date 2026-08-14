package io.github.yourname.cycbercompany.skill;

/**
 * Compact status line for one marketplace source.
 */
public record SkillSourceSummaryView(
        String id,
        String label,
        String description,
        String sourceType,
        int count,
        String status,
        String url,
        String note) {
}
