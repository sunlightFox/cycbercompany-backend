package io.github.yourname.agentstudio.skill;

import jakarta.validation.constraints.NotBlank;

/** Installs one public skill from SkillHub. */
public record InstallSkillHubSkillCommand(
        @NotBlank String reference,
        String id,
        Boolean enabled,
        Boolean overwrite) {
}
