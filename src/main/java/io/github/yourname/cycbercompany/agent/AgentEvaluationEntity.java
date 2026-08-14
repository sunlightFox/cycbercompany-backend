package io.github.yourname.cycbercompany.agent;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity(name = "agent_evaluation")
@Table(name = "agent_evaluation")
public class AgentEvaluationEntity {

    @Id
    private String id;
    private String tenantId;
    private String agentId;
    private String versionId;
    private String manifestDigest;
    private String suiteId;
    private double score;
    private boolean passed;
    @Lob
    private String reportJson;
    private String createdBy;
    private Instant createdAt;

    protected AgentEvaluationEntity() {
    }

    public AgentEvaluationEntity(
            String id,
            String tenantId,
            String agentId,
            String versionId,
            String manifestDigest,
            String suiteId,
            double score,
            boolean passed,
            String reportJson,
            String createdBy,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.versionId = versionId;
        this.manifestDigest = manifestDigest;
        this.suiteId = suiteId;
        this.score = score;
        this.passed = passed;
        this.reportJson = reportJson;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String agentId() { return agentId; }
    public String versionId() { return versionId; }
    public String manifestDigest() { return manifestDigest; }
    public String suiteId() { return suiteId; }
    public double score() { return score; }
    public boolean passed() { return passed; }
    public String reportJson() { return reportJson; }
    public String createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
}
