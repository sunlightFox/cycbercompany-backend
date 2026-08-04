package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.node.CodingRunEvidenceView;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证 JPA 映射与 outbox 查询在真实 Spring/H2 环境中可用。
 *
 * <p>轮询间隔在本测试中拉长，避免后台 dispatcher 抢走本测试主动领取的消息。
 */
@SpringBootTest(properties = {
        "app.run.outbox-poll-ms=3600000",
        "app.run.recovery-poll-ms=3600000",
        "app.run.lease-renew-ms=3600000"
})
class RunExecutionPersistenceIntegrationTest {

    @Autowired
    private RunExecutionTaskService tasks;

    @Autowired
    private RunExecutionOutboxService outbox;

    @Autowired
    private RunExecutionOutboxRepository outboxRepository;

    @Autowired
    private RunWorkflowCheckpointService workflowCheckpoints;

    @Test
    void persistedTaskCanBeLeasedAndOutboxCanBeClaimedAndAcknowledged() {
        String runId = "run-p3-" + UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity(
                runId, "tenant-p3", "user-p3", "conversation-p3", "model", "agent", Instant.now());

        tasks.createReady(run);
        assertThat(tasks.claim(runId, "lease-1")).isPresent();
        assertThat(tasks.claim(runId, "lease-2")).isEmpty();

        outbox.enqueue(run);
        // Spring 测试上下文会复用同一个 H2 数据库，其他集成测试可能留下更早的待投递消息。
        // 这里验证的是“本消息最终可被持久化 dispatcher 领取”，不能把分页大小设为 1 而制造测试顺序依赖。
        RunExecutionOutboxService.ClaimedMessage message = outbox.claimPending(100).stream()
                .filter(item -> item.runId().equals(runId))
                .findFirst()
                .orElseThrow();
        outbox.markProcessed(message.id(), message.leaseId());

        assertThat(outboxRepository.findById(message.id()))
                .get()
                .extracting(RunExecutionOutboxEntity::status)
                .isEqualTo(RunExecutionOutboxStatus.PROCESSED);
    }

    @Test
    void renewsOnlyTheLeaseOwnedByThisWorkerAndStopsAfterTerminalCompletion() throws Exception {
        String runId = "run-lease-renew-" + UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity(
                runId, "tenant-p3", "user-p3", "conversation-p3", "model", "agent", Instant.now());
        tasks.createReady(run);
        assertThat(tasks.claim(runId, "lease-owned")).isPresent();
        Instant beforeRenewal = tasks.find(runId).orElseThrow().leaseUntil();

        Thread.sleep(5);
        tasks.renewOwnedLeases();
        Instant afterRenewal = tasks.find(runId).orElseThrow().leaseUntil();
        assertThat(afterRenewal).isAfter(beforeRenewal);

        tasks.completeFromRun(runId, RunStatus.SUCCEEDED);
        tasks.renewOwnedLeases();
        assertThat(tasks.find(runId).orElseThrow())
                .extracting(RunExecutionTaskEntity::status, RunExecutionTaskEntity::leaseUntil)
                .containsExactly(RunExecutionTaskStatus.SUCCEEDED, null);
    }

    @Test
    void exposesOnlyASafeTenantScopedTaskSummaryInTheWorkflowCheckpoint() {
        String runId = "run-workflow-task-" + UUID.randomUUID();
        ActorContext actor = new ActorContext("tenant-workflow", "user-workflow", Set.of(), Set.of());
        AgentRunEntity run = new AgentRunEntity(
                runId, actor.tenantId(), actor.userId(), "conversation-workflow", "model", "agent", Instant.now());
        tasks.createReady(run);
        assertThat(tasks.claim(runId, "secret-lease-token")).isPresent();
        workflowCheckpoints.initialize(runId, "Check worker state", ".", actor);

        RunWorkflowCheckpointView workflow = workflowCheckpoints.get(runId, actor);

        assertThat(workflow.executionTask()).isNotNull();
        assertThat(workflow.executionTask())
                .extracting(RunExecutionTaskView::runId, RunExecutionTaskView::status, RunExecutionTaskView::attempt)
                .containsExactly(runId, RunExecutionTaskStatus.RUNNING, 1);
        assertThat(workflow.executionTask().leaseUntil()).isNotNull();
        assertThat(workflow.executionTask().recoveryRequired()).isFalse();
        assertThat(RunExecutionTaskView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("leaseId", "lastError");

        assertThat(tasks.view(runId, new ActorContext("other-tenant", "user", Set.of(), Set.of()))).isEmpty();
    }

    @Test
    void persistsStructuredCodingStepsAndRestoresThemThroughTheSafeWorkflowView() {
        String runId = "run-structured-workflow-" + UUID.randomUUID();
        ActorContext actor = new ActorContext("tenant-structured", "user-structured", Set.of(), Set.of());
        workflowCheckpoints.initialize(runId, "Implement a small endpoint", "workspace", actor);

        workflowCheckpoints.toolFinished(runId, actor, "project.map", true, null);
        workflowCheckpoints.toolFinished(runId, actor, "fs.write", true, null);
        workflowCheckpoints.toolFinished(runId, actor, "shell.run", true, null);
        workflowCheckpoints.toolFinished(runId, actor, "git.review", true, null);
        List<String> blockers = workflowCheckpoints.finalizeCodingDelivery(
                runId,
                actor,
                new CodingRunEvidenceView(
                        runId, 4, List.of("project.map", "fs.write", "shell.run", "git.review"), -1,
                        List.of("src/Example.java"), true, List.of("src/Example.java"), List.of("shell.run"),
                        List.of("test"), List.of(), false, false, List.of()),
                true);

        RunWorkflowCheckpointView workflow = workflowCheckpoints.get(runId, actor);
        assertThat(blockers).isEmpty();
        assertThat(workflow.plan()).isNotNull();
        assertThat(workflow.plan().projectFilesChanged()).isTrue();
        assertThat(workflow.plan().state(CodingWorkflowStep.DELIVER).status())
                .isEqualTo(CodingWorkflowStepStatus.COMPLETED);
        assertThat(workflow.planJson()).doesNotContain("Example.java", "shell.run");
    }
}
