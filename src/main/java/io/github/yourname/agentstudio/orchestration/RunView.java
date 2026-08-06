package io.github.yourname.agentstudio.orchestration;

import java.time.Instant;

/** 返回给前端的 Run 摘要视图。 */
public record RunView(
        String id,
        String conversationId,
        String modelProfileId,
        String agentId,
        String skillSnapshotDigest,
        String runSpecDigest,
        RunStatus status,
        String finalAnswer,
        String errorMessage,
        Integer queuePosition,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    static RunView from(AgentRunEntity entity) {
        return from(entity, null);
    }

    static RunView from(AgentRunEntity entity, Integer queuePosition) {
        return new RunView(
                entity.id(),
                entity.conversationId(),
                entity.modelProfileId(),
                entity.agentId(),
                entity.skillSnapshotDigest(),
                entity.runSpecDigest(),
                entity.status(),
                entity.finalAnswer(),
                entity.errorMessage(),
                queuePosition,
                entity.createdAt(),
                entity.startedAt(),
                entity.finishedAt());
    }
}
