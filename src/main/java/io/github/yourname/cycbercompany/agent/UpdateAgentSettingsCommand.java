package io.github.yourname.cycbercompany.agent;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateAgentSettingsCommand(
        @Pattern(regexp = "PRIVATE|TEAM|TENANT") String visibility,
        @Pattern(regexp = "ACTIVE|DISABLED") String status,
        @NotNull @PositiveOrZero Long expectedRevision) {
}
