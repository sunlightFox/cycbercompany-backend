package io.github.yourname.agentstudio.knowledge;

import jakarta.validation.constraints.NotBlank;

public record IngestDocumentCommand(@NotBlank String sourceName, @NotBlank String content) {
}
