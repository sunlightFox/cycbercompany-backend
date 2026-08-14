package io.github.yourname.cycbercompany.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

/**
 * Run 入队后的事务 outbox。
 *
 * <p>它和 {@link RunExecutionTaskEntity} 在同一事务中写入：即使应用在事务提交后、
 * 内存队列激活前崩溃，重启后的 dispatcher 仍可看见这条消息并重新安排任务。
 */
@Entity(name = "run_execution_outbox")
public class RunExecutionOutboxEntity {

    @Id
    private String id;
    private String tenantId;
    private String runId;
    private String eventType;

    @Enumerated(EnumType.STRING)
    private RunExecutionOutboxStatus status;

    private int deliveryAttempt;
    private String leaseId;
    private Instant leaseUntil;
    private Instant availableAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant processedAt;

    @Lob
    private String lastError;

    @Version
    private Long version;

    protected RunExecutionOutboxEntity() {
    }

    public RunExecutionOutboxEntity(String id, String tenantId, String runId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.runId = runId;
        this.eventType = "RUN_DISPATCH_REQUESTED";
        this.status = RunExecutionOutboxStatus.PENDING;
        this.availableAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean claim(String nextLeaseId, Instant now, Duration leaseDuration) {
        if (status == RunExecutionOutboxStatus.PROCESSED || status == RunExecutionOutboxStatus.FAILED) {
            return false;
        }
        if (status == RunExecutionOutboxStatus.DISPATCHING
                && leaseUntil != null
                && leaseUntil.isAfter(now)) {
            return false;
        }
        if (availableAt != null && availableAt.isAfter(now)) {
            return false;
        }
        this.status = RunExecutionOutboxStatus.DISPATCHING;
        this.deliveryAttempt++;
        this.leaseId = nextLeaseId;
        this.leaseUntil = now.plus(leaseDuration);
        this.updatedAt = now;
        return true;
    }

    public void processed(String expectedLeaseId, Instant now) {
        requireLease(expectedLeaseId);
        this.status = RunExecutionOutboxStatus.PROCESSED;
        this.processedAt = now;
        clearLease();
        this.updatedAt = now;
    }

    /** 可重试投递失败使用退避时间，不会因临时数据库/队列错误进入紧密死循环。 */
    public void retry(String expectedLeaseId, String error, Instant nextAvailableAt, Instant now) {
        requireLease(expectedLeaseId);
        this.status = RunExecutionOutboxStatus.PENDING;
        this.lastError = error;
        this.availableAt = nextAvailableAt;
        clearLease();
        this.updatedAt = now;
    }

    private void requireLease(String expectedLeaseId) {
        if (leaseId == null || !leaseId.equals(expectedLeaseId)) {
            throw new IllegalStateException("Outbox message lease does not belong to this dispatcher.");
        }
    }

    private void clearLease() {
        this.leaseId = null;
        this.leaseUntil = null;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String runId() { return runId; }
    public String eventType() { return eventType; }
    public RunExecutionOutboxStatus status() { return status; }
    public int deliveryAttempt() { return deliveryAttempt; }
    public String leaseId() { return leaseId; }
    public Instant leaseUntil() { return leaseUntil; }
    public Instant availableAt() { return availableAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant processedAt() { return processedAt; }
    public String lastError() { return lastError; }
}
