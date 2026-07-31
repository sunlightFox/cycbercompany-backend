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
                // API 阅读审计记录不需要看到真实密钥；执行时仍从受控持久化记录读取原始参数。
                SensitiveValueMasker.mask(entity.argumentsJson()),
                SensitiveValueMasker.mask(entity.resultJson()),
                SensitiveValueMasker.mask(entity.errorMessage()),
                entity.createdAt(),
                entity.startedAt(),
                entity.finishedAt());
    }
}
