package io.github.yourname.agentstudio.orchestration;

import java.time.Instant;

public record RunView(
        String id,
        String conversationId,
        String modelProfileId,
        String agentId,
        RunStatus status,
        String finalAnswer,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    static RunView from(AgentRunEntity entity) {
        return new RunView(
                entity.id(),
                entity.conversationId(),
                entity.modelProfileId(),
                entity.agentId(),
                entity.status(),
                entity.finalAnswer(),
                entity.errorMessage(),
                entity.createdAt(),
                entity.startedAt(),
                entity.finishedAt());
    }
}
