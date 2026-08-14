package io.github.yourname.cycbercompany.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.node.CodingRunEvidenceView;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将编排过程中的简明、可恢复状态写入数据库。 */
@Service
public class RunWorkflowCheckpointService {

    private final RunWorkflowCheckpointRepository checkpoints;
    private final ObjectMapper objectMapper;
    private RunExecutionTaskService executionTasks;

    public RunWorkflowCheckpointService(
            RunWorkflowCheckpointRepository checkpoints,
            ObjectMapper objectMapper) {
        this.checkpoints = checkpoints;
        this.objectMapper = objectMapper;
    }

    /** 保持旧的手工构造测试兼容；生产容器会注入任务查询服务。 */
    @Autowired
    void configureExecutionTasks(RunExecutionTaskService executionTasks) {
        this.executionTasks = executionTasks;
    }

    @Transactional
    public void initialize(String runId, String goal, String workspaceScope, ActorContext actor) {
        checkpoints.findById(runId).orElseGet(() -> checkpoints.save(new RunWorkflowCheckpointEntity(
                runId,
                actor.tenantId(),
                goal,
                workspaceScope,
                serialize(CodingWorkflowPlan.initial()),
                Instant.now())));
    }

    @Transactional
    public void phase(String runId, ActorContext actor, RunWorkflowPhase phase) {
        checkpoints.findByRunIdAndTenantId(runId, actor.tenantId()).ifPresent(checkpoint -> {
            checkpoint.phase(phase, Instant.now());
            checkpoints.save(checkpoint);
        });
    }

    @Transactional
    public void toolFinished(String runId, ActorContext actor, String toolName, boolean succeeded, String error) {
        checkpoints.findByRunIdAndTenantId(runId, actor.tenantId()).ifPresent(checkpoint -> {
            Instant now = Instant.now();
            checkpoint.toolFinished(toolName, succeeded, error, now);
            checkpoint.planJson(serialize(
                    restorePlan(checkpoint.planJson()).afterToolResult(toolName, succeeded, now)), now);
            checkpoints.save(checkpoint);
        });
    }

    /**
     * 让交付门禁的最终结论同步回工作流，并返回因步骤证据缺失导致的额外阻塞原因。
     *
     * <p>调用方已经先完成 CodingDeliveryGate 的审计判断；这里不是第二套节点证据系统，
     * 而是确保"已通过门禁"和"计划每一步都完成"不会互相矛盾。
     */
    @Transactional
    public List<String> finalizeCodingDelivery(
            String runId,
            ActorContext actor,
            CodingRunEvidenceView evidence,
            boolean deliveryGatePassed) {
        return checkpoints.findByRunIdAndTenantId(runId, actor.tenantId()).map(checkpoint -> {
            Instant now = Instant.now();
            boolean changedFiles = evidence != null && evidence.changedFiles() != null && !evidence.changedFiles().isEmpty();
            CodingWorkflowPlan plan = restorePlan(checkpoint.planJson())
                    .afterDeliveryEvidence(changedFiles, deliveryGatePassed, now);
            checkpoint.planJson(serialize(plan), now);
            checkpoints.save(checkpoint);
            return plan.deliveryBlockers();
        }).orElse(List.of());
    }

    /** 给工具循环下一轮模型调用的恢复摘要。 */
    @Transactional(readOnly = true)
    public String resumeGuidance(String runId, ActorContext actor) {
        return checkpoints.findByRunIdAndTenantId(runId, actor.tenantId())
                .map(checkpoint -> restorePlan(checkpoint.planJson()).resumeGuidance())
                .orElse("Host workflow checkpoint is unavailable; inspect current state before making changes.");
    }

    @Transactional
    public void failure(String runId, ActorContext actor, String error) {
        checkpoints.findByRunIdAndTenantId(runId, actor.tenantId()).ifPresent(checkpoint -> {
            checkpoint.failure(error, Instant.now());
            checkpoints.save(checkpoint);
        });
    }

    /**
     * 按租户读取一个 Run 的最新检查点，供恢复 UI 和审计接口使用。
     */
    @Transactional(readOnly = true)
    public RunWorkflowCheckpointView get(String runId, ActorContext actor) {
        return checkpoints.findByRunIdAndTenantId(runId, actor.tenantId())
                .map(checkpoint -> RunWorkflowCheckpointView.from(
                        checkpoint,
                        restorePlan(checkpoint.planJson()),
                        executionTasks == null ? null : executionTasks.view(runId, actor).orElse(null)))
                .orElseThrow(() -> new IllegalArgumentException("Workflow checkpoint not found: " + runId));
    }

    /** 兼容旧版数组 JSON 或人工损坏数据；恢复时宁可从安全的待检查状态重新开始。 */
    private CodingWorkflowPlan restorePlan(String planJson) {
        try {
            if (planJson == null || planJson.isBlank() || !planJson.strip().startsWith("{")) {
                return CodingWorkflowPlan.initial();
            }
            CodingWorkflowPlan plan = objectMapper.readValue(planJson, CodingWorkflowPlan.class);
            return plan.schemaVersion() == CodingWorkflowPlan.CURRENT_SCHEMA_VERSION
                    ? plan
                    : CodingWorkflowPlan.initial();
        } catch (Exception ex) {
            return CodingWorkflowPlan.initial();
        }
    }

    private String serialize(CodingWorkflowPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize workflow plan.", ex);
        }
    }
}
