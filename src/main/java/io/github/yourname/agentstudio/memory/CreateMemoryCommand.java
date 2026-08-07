package io.github.yourname.agentstudio.memory;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateMemoryCommand(
        @NotBlank String agentId,
        @NotNull MemoryType type,
        @NotBlank @Size(max = 4000) String content,
        @DecimalMin("0.0") @DecimalMax("1.0") Double importance,
        @Size(max = 200) String sourceConversationId,
        @Size(max = 200) String sourceRunId,
        @Size(max = 2000) String evidenceSummary,
        Instant expiresAt,
        @Size(max = 200) String personaId) {

    public CreateMemoryCommand(
            String agentId,
            MemoryType type,
            String content,
            Double importance,
            String sourceConversationId,
            String sourceRunId,
            String evidenceSummary,
            Instant expiresAt) {
        this(agentId, type, content, importance, sourceConversationId, sourceRunId, evidenceSummary, expiresAt, null);
    }
}
