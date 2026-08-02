package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

/**
 * 可恢复的 Run worker 任务。
 *
 * <p>以前队列只在 JVM 内存里保留 {@code Runnable}，服务重启后排队中的 Run 会失去
 * 调度入口。该实体只存储可恢复事实，真正的运行输入始终从 {@link AgentRunEntity} 的
 * immutable RunSpec 读取，因此不依赖 HTTP 请求对象或 Java 闭包。
 */
@Entity(name = "run_execution_task")
public class RunExecutionTaskEntity {

    /** 一个 Run 只允许有一个执行任务，主键直接复用 runId 可以避免重复入队。 */
    @Id
    private String runId;
    private String tenantId;
    private String conversationId;

    @Enumerated(EnumType.STRING)
    private RunExecutionTaskStatus status;

    /** 每次成功领取 lease 都递增，便于审计重试次数。 */
    private int attempt;
    private String leaseId;
    private Instant leaseUntil;
    private Instant availableAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Lob
    private String lastError;

    /** 乐观锁防止两个应用实例同时把同一任务领取为 RUNNING。 */
    @Version
    private Long version;

    protected RunExecutionTaskEntity() {
    }

    public RunExecutionTaskEntity(String runId, String tenantId, String conversationId, Instant now) {
        this.runId = runId;
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.status = RunExecutionTaskStatus.READY;
        this.availableAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 领取任务时只接受 READY，或者已经过期的 RUNNING lease。
     *
     * <p>正常路径不会重新领取 RUNNING 任务；允许过期 lease 的原因是 worker 可能在
     * 写入开始状态后崩溃。调用方仍需先判断该 Run 是否可以安全恢复，不能靠本方法重放
     * 有副作用的节点操作。
     */
    public boolean claim(String nextLeaseId, Instant now, Duration leaseDuration) {
        if (status.terminal() || status == RunExecutionTaskStatus.WAITING_APPROVAL) {
            return false;
        }
        if (status == RunExecutionTaskStatus.RUNNING && !leaseExpired(now)) {
            return false;
        }
        if (availableAt != null && availableAt.isAfter(now)) {
            return false;
        }
        this.status = RunExecutionTaskStatus.RUNNING;
        this.attempt++;
        this.leaseId = nextLeaseId;
        this.leaseUntil = now.plus(leaseDuration);
        this.updatedAt = now;
        return true;
    }

    /** worker 周期性续租，避免长模型调用被其他实例错误接管。 */
    public boolean renewLease(String expectedLeaseId, Instant now, Duration leaseDuration) {
        if (status != RunExecutionTaskStatus.RUNNING || !expectedLeaseId.equals(leaseId)) {
            return false;
        }
        this.leaseUntil = now.plus(leaseDuration);
        this.updatedAt = now;
        return true;
    }

    public void waitForApproval(Instant now) {
        if (status.terminal()) {
            return;
        }
        this.status = RunExecutionTaskStatus.WAITING_APPROVAL;
        clearLease();
        this.updatedAt = now;
    }

    public void ready(Instant now) {
        if (status.terminal()) {
            return;
        }
        this.status = RunExecutionTaskStatus.READY;
        this.availableAt = now;
        clearLease();
        this.updatedAt = now;
    }

    public void complete(RunStatus runStatus, Instant now) {
        // UNKNOWN 表示曾发生无法确认的外部副作用，不能因为后续 Run 被标为 FAILED
        // 就覆盖掉这条更重要的审计事实。
        if (status == RunExecutionTaskStatus.UNKNOWN) {
            return;
        }
        this.status = switch (runStatus) {
            case SUCCEEDED -> RunExecutionTaskStatus.SUCCEEDED;
            case NEEDS_VERIFICATION -> RunExecutionTaskStatus.NEEDS_VERIFICATION;
            case CANCELLED -> RunExecutionTaskStatus.CANCELLED;
            case FAILED, TIMED_OUT -> RunExecutionTaskStatus.FAILED;
            default -> throw new IllegalArgumentException("Run is not terminal: " + runStatus);
        };
        clearLease();
        this.updatedAt = now;
    }

    /**
     * 仅在没有可靠 continuation/checkpoint 可用时使用。UNKNOWN 不是失败重试信号，
     * 而是明确告诉调用方：可能已经出现了无法确认的外部副作用。
     */
    public void markUnknown(String reason, Instant now) {
        this.status = RunExecutionTaskStatus.UNKNOWN;
        this.lastError = reason;
        clearLease();
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = RunExecutionTaskStatus.CANCELLED;
        clearLease();
        this.updatedAt = now;
    }

    public boolean leaseExpired(Instant now) {
        return leaseUntil == null || !leaseUntil.isAfter(now);
    }

    private void clearLease() {
        this.leaseId = null;
        this.leaseUntil = null;
    }

    public String runId() { return runId; }
    public String tenantId() { return tenantId; }
    public String conversationId() { return conversationId; }
    public RunExecutionTaskStatus status() { return status; }
    public int attempt() { return attempt; }
    public String leaseId() { return leaseId; }
    public Instant leaseUntil() { return leaseUntil; }
    public Instant availableAt() { return availableAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public String lastError() { return lastError; }
}
