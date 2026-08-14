package io.github.yourname.cycbercompany.knowledge;

import jakarta.validation.constraints.NotBlank;

public record CreateKnowledgeBaseCommand(@NotBlank String name, String description) {
}
