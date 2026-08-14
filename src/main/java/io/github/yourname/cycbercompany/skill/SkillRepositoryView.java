package io.github.yourname.cycbercompany.skill;

/**
 * A public GitHub repository that likely contains Agent/Codex skills.
 */
public record SkillRepositoryView(
        String id,
        String name,
        String description,
        String url,
        String defaultBranch,
        int stars,
        String sourceType) {
}
