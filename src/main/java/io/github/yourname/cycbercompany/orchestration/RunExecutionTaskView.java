package io.github.yourname.cycbercompany.orchestration;

import java.time.Instant;

/**
 * 面向控制面的持久任务安全摘要。
 *
 * <p>这里故意不包含 leaseId、RunSpec、工具参数或工具输出。前端只需要知道 worker 的调度状态、
 * 尝试次数和租约何时到期，就能解释“仍在运行”与“需要恢复核对”的区别。
 */
public record RunExecutionTaskView(
        String runId,
        RunExecutionTaskStatus status,
        int attempt,
        Instant leaseUntil,
        Instant availableAt,
        Instant updatedAt,
        boolean recoveryRequired) {

    static RunExecutionTaskView from(RunExecutionTaskEntity task) {
        return new RunExecutionTaskView(
                task.runId(),
                task.status(),
                task.attempt(),
                task.leaseUntil(),
                task.availableAt(),
                task.updatedAt(),
                task.status() == RunExecutionTaskStatus.UNKNOWN);
    }
}
