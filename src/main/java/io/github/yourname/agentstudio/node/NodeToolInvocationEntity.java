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
    private int dispatchAttempt;
    private String argumentsDigest;
    private String idempotencyKey;
    private String policyRevision;
    private String resultDigest;
    private Instant createdAt;
    private Instant deadlineAt;
    private Instant acceptedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant updatedAt;

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
        this.updatedAt = now;
    }

    /** 在控制消息发送前持久化调度事实；attempt 只在明确重新调度读操作时递增。 */
    public void dispatch(
            int attempt,
            Instant deadlineAt,
            String argumentsDigest,
            String idempotencyKey,
            String policyRevision,
            Instant now) {
        this.status = NodeToolInvocationStatus.DISPATCHED;
        this.dispatchAttempt = Math.max(1, attempt);
        this.deadlineAt = deadlineAt;
        this.argumentsDigest = argumentsDigest;
        this.idempotencyKey = idempotencyKey;
        this.policyRevision = policyRevision;
        this.updatedAt = now;
    }

    public void accept(Instant now) {
        if (terminal()) return;
        this.status = NodeToolInvocationStatus.ACCEPTED;
        this.acceptedAt = this.acceptedAt == null ? now : this.acceptedAt;
        this.updatedAt = now;
    }

    public void start(Instant now) {
        if (terminal()) return;
        this.status = NodeToolInvocationStatus.RUNNING;
        this.acceptedAt = this.acceptedAt == null ? now : this.acceptedAt;
        this.startedAt = this.startedAt == null ? now : this.startedAt;
        this.updatedAt = now;
    }

    public void succeed(String resultJson, String resultDigest, Instant now) {
        this.status = NodeToolInvocationStatus.SUCCEEDED;
        this.resultJson = resultJson;
        // An approval-required placeholder is recorded as a failed/intermediate
        // invocation before the human decision. Once the same invocation
        // completes successfully, that placeholder must not remain as the
        // final error attached to a successful result.
        this.errorMessage = null;
        this.resultDigest = resultDigest;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    /** 兼容现有调用方；新的协议路径同时保存结果摘要。 */
    public void succeed(String resultJson, Instant now) {
        succeed(resultJson, null, now);
    }

    public void fail(NodeToolInvocationStatus status, String errorMessage, Instant now) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    public void unknown(String errorMessage, Instant now) {
        fail(NodeToolInvocationStatus.UNKNOWN, errorMessage, now);
    }

    public boolean terminal() {
        return status == NodeToolInvocationStatus.SUCCEEDED
                || status == NodeToolInvocationStatus.FAILED
                || status == NodeToolInvocationStatus.TIMED_OUT
                || status == NodeToolInvocationStatus.UNKNOWN
                || status == NodeToolInvocationStatus.CANCELLED;
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
    public int dispatchAttempt() { return dispatchAttempt; }
    public String argumentsDigest() { return argumentsDigest; }
    public String idempotencyKey() { return idempotencyKey; }
    public String policyRevision() { return policyRevision; }
    public String resultDigest() { return resultDigest; }
    public Instant createdAt() { return createdAt; }
    public Instant deadlineAt() { return deadlineAt; }
    public Instant acceptedAt() { return acceptedAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public Instant updatedAt() { return updatedAt; }
}
