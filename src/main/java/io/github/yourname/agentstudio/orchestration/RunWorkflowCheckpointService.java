package io.github.yourname.agentstudio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                defaultPlan(workspaceScope),
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
            checkpoint.toolFinished(toolName, succeeded, error, Instant.now());
            checkpoints.save(checkpoint);
        });
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
                        executionTasks == null ? null : executionTasks.view(runId, actor).orElse(null)))
                .orElseThrow(() -> new IllegalArgumentException("Workflow checkpoint not found: " + runId));
    }

    private String defaultPlan(String workspaceScope) {
        try {
            return objectMapper.writeValueAsString(List.of(
                    Map.of("step", "inspect", "status", "pending"),
                    Map.of("step", "implement", "status", "pending"),
                    Map.of("step", "verify", "status", "pending"),
                    Map.of("workspaceScope", workspaceScope == null ? "." : workspaceScope)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize workflow plan.", ex);
        }
    }
}
