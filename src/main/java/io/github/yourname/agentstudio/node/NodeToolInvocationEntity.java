package io.github.yourname.agentstudio.node;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

@Entity(name = "node_tool_invocation")
public class NodeToolInvocationEntity {

    @Id
    private String id;
    private String tenantId;
    private String runId;
    private String toolCallId;
    private String nodeId;
    private String toolName;
    @Enumerated(EnumType.STRING)
    private NodeToolInvocationStatus status;
    @Lob
    private String argumentsJson;
    @Lob
    private String resultJson;
    @Lob
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected NodeToolInvocationEntity() {
    }

    public NodeToolInvocationEntity(
            String id,
            String tenantId,
            String runId,
            String toolCallId,
            String nodeId,
            String toolName,
            String argumentsJson,
            Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.runId = runId;
        this.toolCallId = toolCallId;
        this.nodeId = nodeId;
        this.toolName = toolName;
        this.argumentsJson = argumentsJson;
        this.status = NodeToolInvocationStatus.REQUESTED;
        this.createdAt = now;
    }

    public void start(Instant now) {
        this.status = NodeToolInvocationStatus.RUNNING;
        this.startedAt = now;
    }

    public void succeed(String resultJson, Instant now) {
        this.status = NodeToolInvocationStatus.SUCCEEDED;
        this.resultJson = resultJson;
        this.finishedAt = now;
    }

    public void fail(NodeToolInvocationStatus status, String errorMessage, Instant now) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String runId() { return runId; }
    public String toolCallId() { return toolCallId; }
    public String nodeId() { return nodeId; }
    public String toolName() { return toolName; }
    public NodeToolInvocationStatus status() { return status; }
    public String argumentsJson() { return argumentsJson; }
    public String resultJson() { return resultJson; }
    public String errorMessage() { return errorMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}
