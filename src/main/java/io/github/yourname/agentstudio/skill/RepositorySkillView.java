package io.github.yourname.agentstudio.skill;

/**
 * A skill candidate discovered inside a repository.
 */
public record RepositorySkillView(
        String name,
        String description,
        String repositoryUrl,
        String ref,
        String path,
        String installId) {
}
