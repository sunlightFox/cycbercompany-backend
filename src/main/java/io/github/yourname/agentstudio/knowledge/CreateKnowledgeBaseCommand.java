package io.github.yourname.agentstudio.knowledge;

import jakarta.validation.constraints.NotBlank;

public record CreateKnowledgeBaseCommand(@NotBlank String name, String description) {
}
