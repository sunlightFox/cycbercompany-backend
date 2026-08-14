package io.github.yourname.cycbercompany.skill;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for installing a skill from a public GitHub repository.
 *
 * @param repoUrl repository URL, for example {@code https://github.com/anthropics/skills}
 * @param ref branch, tag, or commit SHA; defaults to {@code main}
 * @param path optional subdirectory containing {@code SKILL.md}; required when
 *             one repository contains many skills
 * @param id optional local id; defaults to a safe id derived from the skill name
 * @param enabled whether the installed skill should be selectable by agents
 * @param overwrite whether to replace an existing local skill with the same id
 */
public record InstallSkillCommand(
        @NotBlank String repoUrl,
        String ref,
        String path,
        String id,
        Boolean enabled,
        Boolean overwrite) {
}
