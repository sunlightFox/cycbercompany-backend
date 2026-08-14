package io.github.yourname.cycbercompany.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

/**
 * 持久化的 Run 事件。
 *
 * <p>每条事件都有 run 内递增的 sequence。前端 SSE 断线重连时用 Last-Event-ID
 * 继续读取 sequence 更大的事件。
 */
@Entity(name = "run_event")
public class RunEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键，不暴露为事件游标。 */
    private Long id;
    /** 租户 ID。 */
    private String tenantId;
    /** 所属 Run。 */
    private String runId;
    /** Run 内单调递增序号，SSE 使用它作为事件 id。 */
    private long sequence;

    // Store the enum name as ordinary text. Hibernate's H2 enum mapping adds a
    // CHECK constraint containing every value, which breaks persisted local
    // databases whenever a new SSE event type is introduced.
    private String type;

    @Lob
    /** 事件载荷，保持为字符串以兼容不同事件结构。 */
    private String payload;
    /** 事件创建时间。 */
    private Instant createdAt;

    protected RunEventEntity() {
    }

    public RunEventEntity(String tenantId, String runId, long sequence, RunEventType type, String payload, Instant createdAt) {
        this.tenantId = tenantId;
        this.runId = runId;
        this.sequence = sequence;
        this.type = type.name();
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long id() { return id; }
    public String tenantId() { return tenantId; }
    public String runId() { return runId; }
    public long sequence() { return sequence; }
    public RunEventType type() { return RunEventType.valueOf(type); }
    public String payload() { return payload; }
    public Instant createdAt() { return createdAt; }
}
