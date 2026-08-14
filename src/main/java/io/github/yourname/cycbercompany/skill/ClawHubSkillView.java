package io.github.yourname.cycbercompany.skill;

/** A public skill returned by the ClawHub registry. */
public record ClawHubSkillView(
        String id,
        String name,
        String description,
        String reference,
        String url,
        long downloads,
        boolean official,
        boolean suspicious,
        String verdict) {
}
