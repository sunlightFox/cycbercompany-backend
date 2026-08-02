package io.github.yourname.agentstudio.execution;

import jakarta.validation.constraints.NotNull;

public record UpdateExecutionSettingsCommand(@NotNull ExecutionMode mode) {
}
