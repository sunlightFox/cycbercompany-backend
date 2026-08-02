package io.github.yourname.agentstudio.orchestration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 控制面启动后的 Run 恢复扫描。
 *
 * <p>READY 任务可安全重新入队，因为其模型循环还未获得 worker lease。RUNNING 但 lease
 * 已过期的任务不重放，避免重启后重复执行文件、浏览器或桌面副作用；它会被显式转成
 * UNKNOWN 并在 Run 事件中留下可审计原因。
 */
@Component
public class RunRecoveryCoordinator {

    private final RunExecutionTaskService tasks;
    private final RunCommandService runs;

    public RunRecoveryCoordinator(
            RunExecutionTaskService tasks,
            @Lazy RunCommandService runs) {
        this.tasks = tasks;
        this.runs = runs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        recoverReadyTasks();
        reconcileExpiredLeases();
    }

    /** 定期处理遗留 lease，防止进程崩溃后任务永久显示为执行中。 */
    @Scheduled(fixedDelayString = "${app.run.recovery-poll-ms:30000}")
    public void reconcileExpiredLeases() {
        for (RunExecutionTaskEntity task : tasks.findExpiredLeases()) {
            runs.markRunRecoveryUnknown(task.runId(),
                    "Worker lease expired before the Run outcome could be safely recovered.");
        }
    }

    private void recoverReadyTasks() {
        for (RunExecutionTaskEntity task : tasks.findRecoverable()) {
            runs.recoverPersistedRun(task.runId());
        }
    }
}
