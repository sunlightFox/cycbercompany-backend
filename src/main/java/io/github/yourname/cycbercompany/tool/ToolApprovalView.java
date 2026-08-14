package io.github.yourname.cycbercompany.tool;

import java.time.Instant;

public record ToolApprovalView(
        String id,
        String runId,
        String toolCallId,
        String bindingId,
        String providerId,
        String providerToolName,
        String argumentsDigest,
        Integer timeoutSeconds,
        String workingDirectory,
        ToolApprovalStatus status,
        String requesterId,
        String reviewerId,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt,
        String errorMessage) {

    static ToolApprovalView from(ToolApprovalEntity entity) {
        return new ToolApprovalView(
                entity.id(), entity.runId(), entity.toolCallId(), entity.bindingId(), entity.providerId(),
                entity.providerToolName(), entity.argumentsDigest(), entity.timeoutSeconds(), entity.workingDirectory(),
                entity.status(), entity.requesterId(), entity.reviewerId(), entity.requestedAt(), entity.expiresAt(),
                entity.decidedAt(), entity.errorMessage());
    }
}
