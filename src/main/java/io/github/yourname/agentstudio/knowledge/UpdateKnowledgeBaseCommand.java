package io.github.yourname.agentstudio.knowledge;

import jakarta.validation.constraints.NotBlank;

public record UpdateKnowledgeBaseCommand(@NotBlank String name, String description) {
}
