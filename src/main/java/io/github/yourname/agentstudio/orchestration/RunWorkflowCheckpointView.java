package io.github.yourname.agentstudio.orchestration;

import java.time.Instant;

/**
 * 面向 API 的编排检查点摘要。
 *
 * <p>这里刻意不返回工具参数、原始网页内容和完整工具输出，避免把密钥或长日志写入控制面响应。
 */
public record RunWorkflowCheckpointView(
        String runId,
        String workspaceScope,
        String goal,
        String planJson,
        RunWorkflowPhase phase,
        String lastToolName,
        int completedToolCalls,
        int failedToolCalls,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        RunExecutionTaskView executionTask) {

    static RunWorkflowCheckpointView from(
            RunWorkflowCheckpointEntity checkpoint,
            RunExecutionTaskView executionTask) {
        return new RunWorkflowCheckpointView(
                checkpoint.runId(),
                checkpoint.workspaceScope(),
                checkpoint.goal(),
                checkpoint.planJson(),
                checkpoint.phase(),
                checkpoint.lastToolName(),
                checkpoint.completedToolCalls(),
                checkpoint.failedToolCalls(),
                checkpoint.lastError(),
                checkpoint.createdAt(),
                checkpoint.updatedAt(),
                executionTask);
    }
}
