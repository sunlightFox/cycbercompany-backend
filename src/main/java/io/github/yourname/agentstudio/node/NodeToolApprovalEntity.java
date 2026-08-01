package io.github.yourname.agentstudio.node;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Entity(name = "node_tool_approval")
public class NodeToolApprovalEntity {
    @Id
    private String id;
    private String tenantId;
    private String nodeId;
    private String toolName;
    private String runId;
    private String toolCallId;
    @Lob
    private String argumentsJson;
    private String argumentsSha256;
    private String requiredRole;
    private Integer timeoutSeconds;
    @Enumerated(EnumType.STRING)
    private NodeToolApprovalStatus status;
    private String requestedBy;
    private String decidedBy;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant decidedAt;
    private Instant executedAt;
    private String executionStatus;
    @Lob
    private String resultJson;
    @Lob
    private String errorMessage;
    @Version
    private Long version;

    protected NodeToolApprovalEntity() {}

    public NodeToolApprovalEntity(
            String id,
            String tenantId,
            String nodeId,
            String toolName,
            String argumentsJson,
            Integer timeoutSeconds,
            String requestedBy,
            Instant now) {
        this(
                id,
                tenantId,
                nodeId,
                toolName,
                argumentsJson,
                sha256(argumentsJson),
                "NODE_TOOL_APPROVER",
                timeoutSeconds,
                requestedBy,
                now,
                Instant.MAX);
    }

    public NodeToolApprovalEntity(
            String id,
            String tenantId,
            String nodeId,
            String toolName,
            String argumentsJson,
            String argumentsSha256,
            String requiredRole,
            Integer timeoutSeconds,
            String requestedBy,
            Instant now,
            Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.nodeId = nodeId;
        this.toolName = toolName;
        this.argumentsJson = argumentsJson;
        this.argumentsSha256 = argumentsSha256;
        this.requiredRole = requiredRole;
        this.timeoutSeconds = timeoutSeconds;
        this.requestedBy = requestedBy;
        this.createdAt = now;
        this.expiresAt = expiresAt;
        this.status = NodeToolApprovalStatus.PENDING;
    }

    public void decide(NodeToolApprovalStatus decision, String actor, Instant now) {
        if (status != NodeToolApprovalStatus.PENDING) {
            throw new NodeToolApprovalConflictException("Node tool approval has already been decided: " + id);
        }
        this.status = decision;
        this.decidedBy = actor;
        this.decidedAt = now;
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public void expire(String actor, Instant now) {
        if (status != NodeToolApprovalStatus.PENDING) {
            throw new NodeToolApprovalConflictException("Node tool approval has already been decided: " + id);
        }
        status = NodeToolApprovalStatus.EXPIRED;
        decidedBy = actor;
        decidedAt = now;
    }

    public void recordExecution(String executionStatus, String resultJson, String errorMessage, Instant now) {
        this.executionStatus = executionStatus;
        this.resultJson = resultJson;
        this.errorMessage = errorMessage;
        this.executedAt = now;
    }

    public void linkToRun(String runId, String toolCallId) {
        if (status != NodeToolApprovalStatus.PENDING) {
            throw new NodeToolApprovalConflictException("Cannot link a decided approval to a run: " + id);
        }
        this.runId = runId;
        this.toolCallId = toolCallId;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String nodeId() { return nodeId; }
    public String toolName() { return toolName; }
    public String runId() { return runId; }
    public String toolCallId() { return toolCallId; }
    public String argumentsJson() { return argumentsJson; }
    public String argumentsSha256() { return argumentsSha256; }
    public String requiredRole() { return requiredRole; }
    public Integer timeoutSeconds() { return timeoutSeconds; }
    public NodeToolApprovalStatus status() { return status; }
    public String requestedBy() { return requestedBy; }
    public String decidedBy() { return decidedBy; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant decidedAt() { return decidedAt; }
    public Instant executedAt() { return executedAt; }
    public String executionStatus() { return executionStatus; }
    public String resultJson() { return resultJson; }
    public String errorMessage() { return errorMessage; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
