package io.github.yourname.agentstudio.node;
import java.time.Instant;

public record NodeToolApprovalView(
        String id,
        String nodeId,
        String toolName,
        String runId,
        String toolCallId,
        String argumentsJson,
        Integer timeoutSeconds,
        NodeToolApprovalStatus status,
        String requestedBy,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt,
        Instant executedAt,
        String executionStatus,
        String resultJson,
        String errorMessage) {

    static NodeToolApprovalView from(NodeToolApprovalEntity entity) {
        return new NodeToolApprovalView(
                entity.id(),
                entity.nodeId(),
                entity.toolName(),
                entity.runId(),
                entity.toolCallId(),
                // 审批页面应能看清操作意图，但不能把命令中的密钥原样显示出来。
                SensitiveValueMasker.mask(entity.argumentsJson()),
                entity.timeoutSeconds(),
                entity.status(),
                entity.requestedBy(),
                entity.decidedBy(),
                entity.createdAt(),
                entity.decidedAt(),
                entity.executedAt(),
                entity.executionStatus(),
                SensitiveValueMasker.mask(entity.resultJson()),
                SensitiveValueMasker.mask(entity.errorMessage()));
    }
}
