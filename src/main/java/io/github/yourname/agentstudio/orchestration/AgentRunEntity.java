package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
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
}
