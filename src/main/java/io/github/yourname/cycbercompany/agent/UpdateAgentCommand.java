package io.github.yourname.cycbercompany.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAgentCommand(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 240) String description,
        @NotBlank @Size(max = 12000) String systemPrompt,
        @Size(max = 120) String defaultModelProfileId) {

    /** Compatibility constructor for clients that only update the basic profile. */
    public UpdateAgentCommand(String name, String description, String systemPrompt) {
        this(name, description, systemPrompt, null);
    }
}
