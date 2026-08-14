package io.github.yourname.cycbercompany.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Runtime configuration for the local knowledge index and its embedding provider. */
public record UpdateKnowledgeSettingsCommand(
        @NotNull Boolean embeddingEnabled,
        String embeddingModel,
        String embeddingBaseUrl,
        String embeddingCredentialEnv,
        String apiKey,
        @NotBlank String vectorStore,
        @Min(1) @Max(16_384) int chunkSize,
        @Min(0) @Max(8_192) int chunkOverlap) {
}
