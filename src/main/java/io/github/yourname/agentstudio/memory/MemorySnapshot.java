package io.github.yourname.agentstudio.memory;

import java.time.Instant;

/** Immutable memory content captured when a Run is created. */
public record MemorySnapshot(
        String id,
        MemoryType type,
        String content,
        double confidence,
        double importance,
        Instant expiresAt) {
}
