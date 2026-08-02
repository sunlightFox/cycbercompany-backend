package io.github.yourname.agentstudio.skill;

import jakarta.validation.constraints.NotBlank;

/** Creates a local Skill draft from reviewed SKILL.md content. */
public record CreateSkillCommand(
        @NotBlank String id,
        @NotBlank String skillMarkdown,
        Boolean enabled,
        Boolean overwrite) {
}
