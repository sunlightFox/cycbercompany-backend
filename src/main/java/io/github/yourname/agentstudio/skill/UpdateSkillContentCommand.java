package io.github.yourname.agentstudio.skill;

import jakarta.validation.constraints.NotBlank;

/** Replaces the reviewed instruction file of a locally installed Skill. */
public record UpdateSkillContentCommand(@NotBlank String skillMarkdown, Boolean enabled) {
}
