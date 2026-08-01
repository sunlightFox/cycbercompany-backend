package io.github.yourname.agentstudio.tool;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

/** 模型工具调用的通用、精确参数审批记录。 */
@Entity(name = "tool_approval")
class ToolApprovalEntity {

    @Id
    private String id;
    private String tenantId;
    private String requesterId;
    private String reviewerId;
    private String runId;
    private String toolCallId;
    private String bindingId;
    private String providerId;
    private String providerToolName;
    @Lob
    private String bindingJson;
    @Lob
    private String argumentsJson;
    private String argumentsDigest;
    private Integer timeoutSeconds;
    private String workingDirectory;
    @Enumerated(EnumType.STRING)
    private ToolApprovalStatus status;
    private Instant requestedAt;
    private Instant expiresAt;
    private Instant decidedAt;
    @Lob
    private String resultJson;
    private String errorMessage;

    protected ToolApprovalEntity() {
    }

    ToolApprovalEntity(
            String id,
            String tenantId,
            String requesterId,
            String runId,
            String toolCallId,
            ResolvedToolBinding binding,
            String bindingJson,
            String argumentsJson,
            String argumentsDigest,
            Integer timeoutSeconds,
            String workingDirectory,
            Instant requestedAt,
            Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.requesterId = requesterId;
        this.runId = runId;
        this.toolCallId = toolCallId;
        this.bindingId = binding.bindingId();
        this.providerId = binding.providerId();
        this.providerToolName = binding.providerToolName();
        this.bindingJson = bindingJson;
        this.argumentsJson = argumentsJson;
        this.argumentsDigest = argumentsDigest;
        this.timeoutSeconds = timeoutSeconds;
        this.workingDirectory = workingDirectory;
        this.status = ToolApprovalStatus.REQUESTED;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
    }

    void decide(boolean approved, String reviewerId, Instant now) {
        if (status != ToolApprovalStatus.REQUESTED) {
            throw new IllegalStateException("Tool approval was already decided: " + id);
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            status = ToolApprovalStatus.EXPIRED;
            decidedAt = now;
            throw new IllegalStateException("Tool approval has expired: " + id);
        }
        this.reviewerId = reviewerId;
        this.decidedAt = now;
        this.status = approved ? ToolApprovalStatus.APPROVED : ToolApprovalStatus.REJECTED;
    }

    void complete(ToolProviderResult result) {
        this.status = result.succeeded() ? ToolApprovalStatus.SUCCEEDED : ToolApprovalStatus.FAILED;
        this.errorMessage = result.errorMessage();
    }

    void resultJson(String resultJson) { this.resultJson = resultJson; }

    String id() { return id; }
    String tenantId() { return tenantId; }
    String requesterId() { return requesterId; }
    String reviewerId() { return reviewerId; }
    String runId() { return runId; }
    String toolCallId() { return toolCallId; }
    String bindingId() { return bindingId; }
    String providerId() { return providerId; }
    String providerToolName() { return providerToolName; }
    String bindingJson() { return bindingJson; }
    String argumentsJson() { return argumentsJson; }
    String argumentsDigest() { return argumentsDigest; }
    Integer timeoutSeconds() { return timeoutSeconds; }
    String workingDirectory() { return workingDirectory; }
    ToolApprovalStatus status() { return status; }
    Instant requestedAt() { return requestedAt; }
    Instant expiresAt() { return expiresAt; }
    Instant decidedAt() { return decidedAt; }
    String resultJson() { return resultJson; }
    String errorMessage() { return errorMessage; }
}
