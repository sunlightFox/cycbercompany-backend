package io.github.yourname.cycbercompany.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.time.Instant;

/** Durable model context for a coding run paused on a node-tool approval. */
@Entity(name = "coding_run_continuation")
public class CodingRunContinuationEntity {

    @Id
    private String runId;
    private String tenantId;
    private String nodeId;
    private String workingDirectory;
    private String approvalId;
    private String toolCallId;
    @Lob
    private String messagesJson;
    @Lob
    private String evidenceJson;
    @Lob
    private String webResultsJson;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    protected CodingRunContinuationEntity() {
    }

    public CodingRunContinuationEntity(
            String runId,
            String tenantId,
            String nodeId,
            String workingDirectory,
            String approvalId,
            String toolCallId,
            String messagesJson,
            String evidenceJson,
            String webResultsJson,
            Instant now) {
        this.runId = runId;
        this.tenantId = tenantId;
        this.nodeId = nodeId;
        this.workingDirectory = workingDirectory;
        this.approvalId = approvalId;
        this.toolCallId = toolCallId;
        this.messagesJson = messagesJson;
        this.evidenceJson = evidenceJson == null || evidenceJson.isBlank() ? "[]" : evidenceJson;
        this.webResultsJson = webResultsJson == null || webResultsJson.isBlank() ? "[]" : webResultsJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Compatibility constructor for continuations created before retrieval candidates were persisted. */
    public CodingRunContinuationEntity(
            String runId,
            String tenantId,
            String nodeId,
            String workingDirectory,
            String approvalId,
            String toolCallId,
            String messagesJson,
            Instant now) {
        this(runId, tenantId, nodeId, workingDirectory, approvalId, toolCallId, messagesJson, "[]", "[]", now);
    }

    public String runId() { return runId; }
    public String tenantId() { return tenantId; }
    public String nodeId() { return nodeId; }
    public String workingDirectory() { return workingDirectory; }
    public String approvalId() { return approvalId; }
    public String toolCallId() { return toolCallId; }
    public String messagesJson() { return messagesJson; }
    public String evidenceJson() { return evidenceJson; }
    public String webResultsJson() { return webResultsJson; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
