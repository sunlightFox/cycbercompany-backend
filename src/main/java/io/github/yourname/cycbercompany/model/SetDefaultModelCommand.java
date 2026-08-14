package io.github.yourname.cycbercompany.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Changes the global fallback model used when a run does not specify
 * {@code modelProfileId}.
 */
public record SetDefaultModelCommand(@NotBlank String modelProfileId) {
}
