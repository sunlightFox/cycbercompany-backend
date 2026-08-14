package io.github.yourname.cycbercompany.knowledge;

import jakarta.validation.constraints.NotBlank;

public record UpdateKnowledgeBaseCommand(@NotBlank String name, String description) {
}
