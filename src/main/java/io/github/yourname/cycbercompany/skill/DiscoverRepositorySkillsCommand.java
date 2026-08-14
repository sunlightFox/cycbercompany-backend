package io.github.yourname.cycbercompany.skill;

import jakarta.validation.constraints.NotBlank;

/**
 * Lists candidate skill directories inside a GitHub repository.
 */
public record DiscoverRepositorySkillsCommand(@NotBlank String repoUrl, String ref, Integer limit) {
}
