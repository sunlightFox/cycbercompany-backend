package io.github.yourname.agentstudio.tool;

import jakarta.validation.constraints.NotBlank;

public record WebSearchCommand(@NotBlank String query, Integer limit) {
}
