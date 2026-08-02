package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Run 任务状态机的唯一入口。
 *
 * <p>把 lease、取消、审批暂停和终态同步集中在这里，可以防止不同 controller/worker
 * 分别直接改实体，造成 Run 状态与调度状态不一致。
 */
@Service
public class RunExecutionTaskService {

    /** 模型、检索、工具调用可能较长，lease 用于故障接管而不是秒级心跳。 */
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    private final RunExecutionTaskRepository tasks;
    /**
     * 只记录当前 JVM 成功领取的租约。续租必须带上 leaseId，不能把数据库中所有 RUNNING
     * 任务无条件延长，否则会掩盖已经失联的 worker。
     */
    private final ConcurrentMap<String, String> ownedLeases = new ConcurrentHashMap<>();

    public RunExecutionTaskService(RunExecutionTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional
    public RunExecutionTaskEntity createReady(AgentRunEntity run) {
        return tasks.findById(run.id()).orElseGet(() -> {
            // Run 创建后，下一个事务可能立刻由 outbox dispatcher 或恢复器执行 claim。
            // 显式 flush 让“任务已 READY”在方法返回时已真实写入数据库，而不是依赖事务提交
            // 时机；这样既缩小本机快速调度的可见性窗口，也让故障恢复路径观察到一致状态。
            return tasks.saveAndFlush(new RunExecutionTaskEntity(
                    run.id(), run.tenantId(), run.conversationId(), Instant.now()));
        });
    }

    @Transactional
    public Optional<RunExecutionTaskEntity> claim(String runId, String leaseId) {
        Instant now = Instant.now();
        Optional<RunExecutionTaskEntity> claimed = tasks.findByRunIdForUpdate(runId)
                .filter(task -> task.claim(leaseId, now, DEFAULT_LEASE))
                .map(tasks::saveAndFlush);
        claimed.ifPresent(task -> ownedLeases.put(task.runId(), task.leaseId()));
        return claimed;
    }

    @Transactional
    public boolean renewLease(String runId, String leaseId) {
        Instant now = Instant.now();
        boolean renewed = tasks.findByRunIdForUpdate(runId)
                .filter(task -> task.renewLease(leaseId, now, DEFAULT_LEASE))
                .map(tasks::save)
                .isPresent();
        if (!renewed) {
            ownedLeases.remove(runId, leaseId);
        }
        return renewed;
    }

    /**
     * 定期延长本机正在执行的 Run。默认每分钟续租一次，而租约有效期为五分钟，
     * 给短暂的数据库抖动留下余量；续租失败就立即放弃本地所有权，交给恢复器按安全策略处理。
     */
    @Scheduled(fixedDelayString = "${app.run.lease-renew-ms:60000}")
    @Transactional
    public void renewOwnedLeases() {
        // 这里直接在当前事务内更新，避免定时方法调用本类的 @Transactional 方法时绕过 Spring 代理。
        ownedLeases.forEach((runId, leaseId) -> {
            boolean renewed = tasks.findByRunIdForUpdate(runId)
                    .filter(task -> task.renewLease(leaseId, Instant.now(), DEFAULT_LEASE))
                    .map(tasks::save)
                    .isPresent();
            if (!renewed) {
                ownedLeases.remove(runId, leaseId);
            }
        });
    }

    @Transactional
    public void waitForApproval(String runId) {
        ownedLeases.remove(runId);
        tasks.findByRunIdForUpdate(runId).ifPresent(task -> {
            task.waitForApproval(Instant.now());
            tasks.save(task);
        });
    }

    @Transactional
    public void ready(String runId) {
        ownedLeases.remove(runId);
        tasks.findByRunIdForUpdate(runId).ifPresent(task -> {
            task.ready(Instant.now());
            tasks.save(task);
        });
    }

    @Transactional
    public void completeFromRun(String runId, RunStatus status) {
        if (status == null || !isTerminal(status)) {
            return;
        }
        ownedLeases.remove(runId);
        tasks.findByRunIdForUpdate(runId).ifPresent(task -> {
            task.complete(status, Instant.now());
            tasks.save(task);
        });
    }

    @Transactional
    public void cancel(String runId) {
        ownedLeases.remove(runId);
        tasks.findByRunIdForUpdate(runId).ifPresent(task -> {
            task.cancel(Instant.now());
            tasks.save(task);
        });
    }

    @Transactional
    public void markUnknown(String runId, String reason) {
        ownedLeases.remove(runId);
        tasks.findByRunIdForUpdate(runId).ifPresent(task -> {
            task.markUnknown(reason, Instant.now());
            tasks.save(task);
        });
    }

    @Transactional(readOnly = true)
    public Optional<RunExecutionTaskEntity> find(String runId) {
        return tasks.findById(runId);
    }

    /**
     * 返回当前租户可见的调度摘要。leaseId 是 worker 私有的围栏令牌，绝不能通过 API 暴露。
     */
    @Transactional(readOnly = true)
    public Optional<RunExecutionTaskView> view(String runId, ActorContext actor) {
        if (actor == null) {
            return Optional.empty();
        }
        return tasks.findById(runId)
                .filter(task -> actor.tenantId().equals(task.tenantId()))
                .map(RunExecutionTaskView::from);
    }

    @Transactional(readOnly = true)
    public List<RunExecutionTaskEntity> findRecoverable() {
        return tasks.findByStatusInOrderByAvailableAtAsc(List.of(RunExecutionTaskStatus.READY));
    }

    @Transactional(readOnly = true)
    public List<RunExecutionTaskEntity> findExpiredLeases() {
        return tasks.findByStatusAndLeaseUntilBefore(RunExecutionTaskStatus.RUNNING, Instant.now());
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.NEEDS_VERIFICATION
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED
                || status == RunStatus.TIMED_OUT;
    }
}
