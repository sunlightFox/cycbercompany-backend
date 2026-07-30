package io.github.yourname.agentstudio.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record UpsertModelProfileCommand(
        @NotBlank String id,
        @NotNull ProviderType providerType,
        @NotBlank String baseUrl,
        @NotBlank String modelName,
        @NotBlank String credentialRef,
        String apiKey,
        @NotEmpty Set<ModelCapability> capabilities,
        boolean enabled) {
}
