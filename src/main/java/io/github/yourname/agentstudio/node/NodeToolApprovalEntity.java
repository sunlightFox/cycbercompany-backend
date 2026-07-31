package io.github.yourname.agentstudio.node;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity(name = "node_tool_approval")
public class NodeToolApprovalEntity {
    @Id
    private String id;
    private String tenantId;
    private String nodeId;
    private String toolName;
    @Lob
    private String argumentsJson;
    private Integer timeoutSeconds;
    @Enumerated(EnumType.STRING)
    private NodeToolApprovalStatus status;
    private String requestedBy;
    private String decidedBy;
    private Instant createdAt;
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
        this.id = id;
        this.tenantId = tenantId;
        this.nodeId = nodeId;
        this.toolName = toolName;
        this.argumentsJson = argumentsJson;
        this.timeoutSeconds = timeoutSeconds;
        this.requestedBy = requestedBy;
        this.createdAt = now;
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

    public void recordExecution(String executionStatus, String resultJson, String errorMessage, Instant now) {
        this.executionStatus = executionStatus;
        this.resultJson = resultJson;
        this.errorMessage = errorMessage;
        this.executedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String nodeId() { return nodeId; }
    public String toolName() { return toolName; }
    public String argumentsJson() { return argumentsJson; }
    public Integer timeoutSeconds() { return timeoutSeconds; }
    public NodeToolApprovalStatus status() { return status; }
    public String requestedBy() { return requestedBy; }
    public String decidedBy() { return decidedBy; }
    public Instant createdAt() { return createdAt; }
    public Instant decidedAt() { return decidedAt; }
    public Instant executedAt() { return executedAt; }
    public String executionStatus() { return executionStatus; }
    public String resultJson() { return resultJson; }
    public String errorMessage() { return errorMessage; }
}
