package io.github.yourname.cycbercompany.execution;

import jakarta.validation.constraints.NotNull;

public record UpdateExecutionSettingsCommand(@NotNull ExecutionMode mode) {
}
