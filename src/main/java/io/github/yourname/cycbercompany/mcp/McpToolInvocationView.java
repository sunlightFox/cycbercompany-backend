package io.github.yourname.cycbercompany.mcp;

import java.time.Instant;

public record McpToolInvocationView(
        String id,
        String runId,
        String connectionId,
        String toolName,
        McpToolInvocationStatus status,
        String argumentKeys,
        String argumentsSha256,
        Integer resultContentItems,
        String errorCategory,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    static McpToolInvocationView from(McpToolInvocationEntity entity) {
        return new McpToolInvocationView(
                entity.id(),
                entity.runId(),
                entity.connectionId(),
                entity.toolName(),
                entity.status(),
                entity.argumentKeys(),
                entity.argumentsSha256(),
                entity.resultContentItems(),
                entity.errorCategory(),
                entity.createdAt(),
                entity.startedAt(),
                entity.finishedAt());
    }
}
