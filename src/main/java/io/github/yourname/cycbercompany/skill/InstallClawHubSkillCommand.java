package io.github.yourname.cycbercompany.skill;

import jakarta.validation.constraints.NotBlank;

/** Installs one public skill from the official ClawHub registry. */
public record InstallClawHubSkillCommand(
        @NotBlank String reference,
        String id,
        Boolean enabled,
        Boolean overwrite) {
}
