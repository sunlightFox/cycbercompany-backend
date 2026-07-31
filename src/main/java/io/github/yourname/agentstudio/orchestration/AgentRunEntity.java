package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

@Entity(name = "agent_run")
public class AgentRunEntity {

    @Id
    private String id;
    private String tenantId;
    private String userId;
    private String conversationId;
    private String modelProfileId;
    private String agentId;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @Lob
    private String finalAnswer;
    @Lob
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected AgentRunEntity() {
    }

    public AgentRunEntity(String id, String tenantId, String userId, String conversationId, String modelProfileId, String agentId, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.modelProfileId = modelProfileId;
        this.agentId = agentId;
        this.status = RunStatus.CREATED;
        this.createdAt = createdAt;
    }

    public void start() {
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void succeed(String finalAnswer) {
        this.status = RunStatus.SUCCEEDED;
        this.finalAnswer = finalAnswer;
        this.finishedAt = Instant.now();
    }

    public void waitForApproval() {
        this.status = RunStatus.WAITING_APPROVAL;
    }

    public void resume() {
        this.status = RunStatus.RUNNING;
    }

    public void fail(String errorMessage) {
        this.status = RunStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    public void cancel() {
        if (status != RunStatus.CREATED && status != RunStatus.RUNNING && status != RunStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("Run cannot be cancelled from status: " + status);
        }
        this.status = RunStatus.CANCELLED;
        this.finishedAt = Instant.now();
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String conversationId() { return conversationId; }
    public String modelProfileId() { return modelProfileId; }
    public String agentId() { return agentId; }
    public RunStatus status() { return status; }
    public String finalAnswer() { return finalAnswer; }
    public String errorMessage() { return errorMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}
