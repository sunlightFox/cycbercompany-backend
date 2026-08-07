package io.github.yourname.agentstudio.agent;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpdateAgentManifestCommand(
        @NotNull Map<String, Object> manifest,
        Long expectedRevision) {
}
