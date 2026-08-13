package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

/**
 * 一次用户请求的持久化运行记录。
 *
 * <p>它保存生命周期状态、最终答案、错误、RunSpec 和 Skill 快照摘要。注意它不是消息表：
 * 用户和助手可见文本仍保存在 conversation/message 中，Run 负责执行过程和审计。
 */
@Entity(name = "agent_run")
public class AgentRunEntity {

    @Id
    /** Run ID。 */
    private String id;
    /** 租户 ID，所有查询必须带它过滤。 */
    private String tenantId;
    /** 发起用户。 */
    private String userId;
    /** 所属会话。 */
    private String conversationId;
    /** 创建时选择的模型 Profile ID。 */
    private String modelProfileId;
    /** 创建时选择的 Agent ID。 */
    private String agentId;

    /**
     * Run 创建时锁定的 Skill Release 列表。
     *
     * <p>保存 JSON 而不是活动安装目录路径，是为了让 Run 在 Skill 升级或卸载后仍能复现当时使用的版本。
     */
    @Lob
    private String skillBindingsJson;

    /** 对绑定 JSON 计算的 SHA-256，方便 API、事件和后续恢复流程快速比较配置是否变化。 */
    @Column(length = 71)
    private String skillSnapshotDigest;

    /** worker 重启后恢复任务所需的完整不可变输入，不依赖创建请求的内存闭包。 */
    @Lob
    private String runSpecJson;

    /** 对原始 RunSpec JSON 计算的摘要；读取时必须复核，防止数据库内容静默变化。 */
    @Column(length = 71)
    private String runSpecDigest;

    @Enumerated(EnumType.STRING)
    /** 当前生命周期状态。 */
    private RunStatus status;

    @Lob
    /** 最终回答缓存，方便查询 Run 时不必重新扫描消息。 */
    private String finalAnswer;
    @Lob
    /** 失败原因或门禁说明。 */
    private String errorMessage;
    /** Run 创建时间。 */
    private Instant createdAt;
    private long lastEventSequence;
    /** Run 实际开始执行时间。 */
    private Instant startedAt;
    /** Run 进入终态时间。 */
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
        this.skillBindingsJson = "[]";
        this.status = RunStatus.QUEUED;
        this.createdAt = createdAt;
    }

    public void bindSkillSnapshot(String bindingsJson, String snapshotDigest) {
        if (status != RunStatus.QUEUED && status != RunStatus.CREATED) {
            throw new IllegalStateException("Skill snapshot must be bound before the Run starts.");
        }
        this.skillBindingsJson = bindingsJson == null || bindingsJson.isBlank() ? "[]" : bindingsJson;
        this.skillSnapshotDigest = snapshotDigest;
    }

    public void bindRunSpec(String specJson, String digest) {
        if (status != RunStatus.QUEUED && status != RunStatus.CREATED) {
            throw new IllegalStateException("RunSpec must be bound before the Run starts.");
        }
        if (specJson == null || specJson.isBlank() || digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("RunSpec JSON and digest must not be blank.");
        }
        this.runSpecJson = specJson;
        this.runSpecDigest = digest;
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

    /**
     * 模型已经结束，但服务端没有收到足以证明交付质量的证据。
     * 这不是运行异常，保留 finalAnswer 让用户能查看工作报告，同时用 errorMessage 保存门禁原因。
     */
    public void needsVerification(String finalAnswer, String explanation) {
        this.status = RunStatus.NEEDS_VERIFICATION;
        this.finalAnswer = finalAnswer;
        this.errorMessage = explanation;
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
        if (status != RunStatus.QUEUED
                && status != RunStatus.CREATED
                && status != RunStatus.RUNNING
                && status != RunStatus.WAITING_APPROVAL) {
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
    public String skillBindingsJson() { return skillBindingsJson; }
    public String skillSnapshotDigest() { return skillSnapshotDigest; }
    public String runSpecJson() { return runSpecJson; }
    public String runSpecDigest() { return runSpecDigest; }
    public RunStatus status() { return status; }
    public String finalAnswer() { return finalAnswer; }
    public String errorMessage() { return errorMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }

    long nextEventSequence() { return ++lastEventSequence; }

    void ensureEventSequenceAtLeast(long value) { lastEventSequence = Math.max(lastEventSequence, value); }
}
