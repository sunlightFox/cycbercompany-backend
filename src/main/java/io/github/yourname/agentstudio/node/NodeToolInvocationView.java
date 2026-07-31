package io.github.yourname.agentstudio.node;

import java.time.Instant;

public record NodeToolInvocationView(
        String id,
        String runId,
        String toolCallId,
        String nodeId,
        String toolName,
        NodeToolInvocationStatus status,
        String argumentsJson,
        String resultJson,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    static NodeToolInvocationView from(NodeToolInvocationEntity entity) {
        return new NodeToolInvocationView(
                entity.id(),
                entity.runId(),
                entity.toolCallId(),
                entity.nodeId(),
                entity.toolName(),
                entity.status(),
                entity.argumentsJson(),
                entity.resultJson(),
                entity.errorMessage(),
                entity.createdAt(),
                entity.startedAt(),
                entity.finishedAt());
    }
}
