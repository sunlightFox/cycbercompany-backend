package io.github.yourname.cycbercompany.agent;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

public record CreateAgentV2Command(
        @NotNull Map<String, Object> manifest,
        @Pattern(regexp = "PRIVATE|TEAM|TENANT") String visibility) {
}
