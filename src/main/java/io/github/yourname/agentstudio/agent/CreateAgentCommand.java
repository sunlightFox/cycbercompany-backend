package io.github.yourname.agentstudio.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAgentCommand(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 240) String description,
        @NotBlank @Size(max = 12000) String systemPrompt) {
}
