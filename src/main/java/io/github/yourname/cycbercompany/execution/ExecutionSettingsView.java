package io.github.yourname.cycbercompany.execution;

import java.time.Instant;

public record ExecutionSettingsView(ExecutionMode mode, Instant updatedAt) {
}
