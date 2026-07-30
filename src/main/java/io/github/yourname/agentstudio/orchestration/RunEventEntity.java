package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

@Entity(name = "run_event")
public class RunEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tenantId;
    private String runId;
    private long sequence;

    @Enumerated(EnumType.STRING)
    private RunEventType type;

    @Lob
    private String payload;
    private Instant createdAt;

    protected RunEventEntity() {
    }

    public RunEventEntity(String tenantId, String runId, long sequence, RunEventType type, String payload, Instant createdAt) {
        this.tenantId = tenantId;
        this.runId = runId;
        this.sequence = sequence;
        this.type = type;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long id() { return id; }
    public String tenantId() { return tenantId; }
    public String runId() { return runId; }
    public long sequence() { return sequence; }
    public RunEventType type() { return type; }
    public String payload() { return payload; }
    public Instant createdAt() { return createdAt; }
}
