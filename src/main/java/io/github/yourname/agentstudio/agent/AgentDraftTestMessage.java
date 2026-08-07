package io.github.yourname.agentstudio.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentDraftTestMessage(
        @Pattern(regexp = "USER|ASSISTANT") String role,
        @NotBlank @Size(max = 12000) String content) {
}
