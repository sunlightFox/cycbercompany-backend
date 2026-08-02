package io.github.yourname.agentstudio.orchestration;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 将已提交的 outbox 消息转换为本地 worker 调度。
 *
 * <p>本类不执行模型工作，只调用 {@link RunCommandService#recoverPersistedRun(String)}
 * 把 Run 放入会话串行队列。这样应用崩溃在数据库提交和内存队列激活之间时，也不会丢失任务。
 */
@Component
public class RunExecutionOutboxDispatcher {

    private final RunExecutionOutboxService outbox;
    private final RunCommandService runs;

    public RunExecutionOutboxDispatcher(
            RunExecutionOutboxService outbox,
            @Lazy RunCommandService runs) {
        this.outbox = outbox;
        this.runs = runs;
    }

    @Scheduled(fixedDelayString = "${app.run.outbox-poll-ms:1000}")
    public void dispatchPending() {
        // 单次批量保持很小：本地队列只负责串行化同一会话，不能被大量历史重放淹没。
        for (RunExecutionOutboxService.ClaimedMessage message : outbox.claimPending(32)) {
            try {
                runs.recoverPersistedRun(message.runId());
                outbox.markProcessed(message.id(), message.leaseId());
            } catch (Exception failure) {
                outbox.retry(message.id(), message.leaseId(), failure);
            }
        }
    }
}
