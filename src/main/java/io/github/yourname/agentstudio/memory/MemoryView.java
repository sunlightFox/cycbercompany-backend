package io.github.yourname.agentstudio.memory;

import java.time.Instant;

public record MemoryView(
        String id,
        String agentId,
        MemoryScope scope,
        MemoryOrigin origin,
        String memoryKey,
        String supersededBy,
        String personaId,
        MemoryType type,
        MemoryStatus status,
        MemorySensitivity sensitivity,
        String content,
        double confidence,
        double importance,
        String sourceConversationId,
        String sourceRunId,
        String evidenceSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt,
        Instant expiresAt,
        long revision) {

    static MemoryView from(MemoryItemEntity item) {
        return new MemoryView(
                item.id(), item.agentId(), item.scope(), item.origin(), item.memoryKey(), item.supersededBy(), item.personaId(), item.type(), item.status(), item.sensitivity(), item.content(),
                item.confidence(), item.importance(), item.sourceConversationId(), item.sourceRunId(),
                item.evidenceSummary(), item.createdAt(), item.updatedAt(), item.lastUsedAt(),
                item.expiresAt(), item.revision());
    }
}
