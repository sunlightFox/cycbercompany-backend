package io.github.yourname.agentstudio.execution;

import java.time.Instant;

public record ExecutionSettingsView(ExecutionMode mode, Instant updatedAt) {
}
