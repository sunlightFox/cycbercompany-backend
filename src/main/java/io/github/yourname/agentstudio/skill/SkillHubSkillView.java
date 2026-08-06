package io.github.yourname.agentstudio.skill;

/** A public skill returned by the SkillHub registry. */
public record SkillHubSkillView(
        String id,
        String name,
        String description,
        String reference,
        String url,
        long downloads,
        boolean verified,
        String source) {
}
