package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 编排循环的持久化工作摘要。
 *
 * <p>模型消息可能很长，完整消息只在审批 continuation 中保存。本实体只保存重启恢复和
 * 用户排障需要的简明事实：目标、初始计划、所在阶段、最近工具和计数。它不能保存工具
 * 参数或原始输出，避免把密钥、长日志和网页内容混入控制面数据库。
 */
@Entity(name = "run_workflow_checkpoint")
public class RunWorkflowCheckpointEntity {

    @Id
    private String runId;
    private String tenantId;
    private String workspaceScope;
    @Lob
    private String goal;
    @Lob
    private String planJson;

    @Enumerated(EnumType.STRING)
    private RunWorkflowPhase phase;
    private String lastToolName;
    private int completedToolCalls;
    private int failedToolCalls;
    @Lob
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected RunWorkflowCheckpointEntity() {
    }

    public RunWorkflowCheckpointEntity(
            String runId,
            String tenantId,
            String goal,
            String workspaceScope,
            String planJson,
            Instant now) {
        this.runId = runId;
        this.tenantId = tenantId;
        this.goal = goal;
        this.workspaceScope = workspaceScope;
        this.planJson = planJson;
        this.phase = RunWorkflowPhase.QUEUED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void phase(RunWorkflowPhase phase, Instant now) {
        this.phase = phase;
        this.updatedAt = now;
    }

    public void toolFinished(String toolName, boolean succeeded, String error, Instant now) {
        this.lastToolName = toolName;
        if (succeeded) {
            this.completedToolCalls++;
        } else {
            this.failedToolCalls++;
            this.lastError = bounded(error);
        }
        this.phase = RunWorkflowPhase.EXECUTING;
        this.updatedAt = now;
    }

    public void failure(String error, Instant now) {
        this.phase = RunWorkflowPhase.FAILED;
        this.lastError = bounded(error);
        this.updatedAt = now;
    }

    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    public String runId() { return runId; }
    public String tenantId() { return tenantId; }
    public String workspaceScope() { return workspaceScope; }
    public String goal() { return goal; }
    public String planJson() { return planJson; }
    public RunWorkflowPhase phase() { return phase; }
    public String lastToolName() { return lastToolName; }
    public int completedToolCalls() { return completedToolCalls; }
    public int failedToolCalls() { return failedToolCalls; }
    public String lastError() { return lastError; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
