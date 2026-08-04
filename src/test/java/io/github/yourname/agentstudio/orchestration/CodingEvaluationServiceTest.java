package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.node.CodingRunEvidenceView;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodingEvaluationServiceTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-1", "user-1", Set.of("USER"), Set.of("agent:run"));

    private AgentRunRepository runs;
    private RunEventRepository events;
    private NodeService nodes;
    private CodingEvaluationService service;

    @BeforeEach
    void setUp() {
        runs = mock(AgentRunRepository.class);
        events = mock(RunEventRepository.class);
        nodes = mock(NodeService.class);
        service = new CodingEvaluationService(runs, events, nodes);
    }

    @Test
    void awardsFullScoreToVerifiedFullStackDelivery() {
        AgentRunEntity run = completedRun();
        CodingRunEvidenceView evidence = evidence(
                List.of("backend/src/TaskController.java", "frontend/src/app.js"),
                List.of("build", "test"),
                List.of(),
                true,
                true,
                true);
        arrange(run, evidence, List.of());

        CodingEvaluationReportView report = service.evaluate(
                run.id(), CodingEvaluationScenario.MINIMAL_FULL_STACK, ACTOR);

        assertThat(report.score()).isEqualTo(100);
        assertThat(report.passed()).isTrue();
        assertThat(report.scenarioLabel()).isEqualTo("最小全栈待办应用");
        assertThat(report.checks()).allMatch(CodingEvaluationReportView.CodingEvaluationCheckView::passed);
    }

    @Test
    void doesNotPassFullStackEvaluationWhenTheReadyServiceWasNotStartedByThisRun() {
        AgentRunEntity run = completedRun();
        CodingRunEvidenceView evidence = evidence(
                List.of("backend/src/TaskController.java", "frontend/src/app.js"),
                List.of("build", "test"),
                List.of(),
                true,
                true,
                false);
        arrange(run, evidence, List.of());

        CodingEvaluationReportView report = service.evaluate(
                run.id(), CodingEvaluationScenario.MINIMAL_FULL_STACK, ACTOR);

        assertThat(report.passed()).isFalse();
        assertThat(report.checks())
                .filteredOn(check -> "requirement-delivery".equals(check.category())
                        || "frontend-backend-integration".equals(check.category()))
                .allMatch(check -> !check.passed());
    }

    @Test
    void doesNotPassFullStackEvaluationWhenServiceReadinessPredatesTheLastCodeChange() {
        AgentRunEntity run = completedRun();
        CodingRunEvidenceView evidence = evidence(
                List.of("backend/src/TaskController.java", "frontend/src/app.js"),
                List.of("build", "test"),
                List.of(),
                true,
                true,
                true,
                false);
        arrange(run, evidence, List.of());

        CodingEvaluationReportView report = service.evaluate(
                run.id(), CodingEvaluationScenario.MINIMAL_FULL_STACK, ACTOR);

        assertThat(report.passed()).isFalse();
        assertThat(report.checks())
                .filteredOn(check -> "requirement-delivery".equals(check.category())
                        || "frontend-backend-integration".equals(check.category()))
                .allMatch(check -> !check.passed());
    }

    @Test
    void acceptsExpectedInitialTestFailureForTheMinimalFixScenario() {
        AgentRunEntity run = completedRun();
        // 初次失败测试会留下 shell.run 审计失败，不能把这类受控诊断误判成越权失败。
        CodingRunEvidenceView evidence = evidence(
                List.of("src/main/java/example/TaxCalculator.java"),
                List.of("test"),
                List.of("shell.run"),
                false,
                false,
                false);
        arrange(run, evidence, List.of());

        CodingEvaluationReportView report = service.evaluate(
                run.id(), CodingEvaluationScenario.FAILED_TEST_MINIMAL_FIX, ACTOR);

        assertThat(report.score()).isEqualTo(100);
        assertThat(report.passed()).isTrue();
    }

    @Test
    void requiresWaitingAndResumedEventsForLongTaskRecovery() {
        AgentRunEntity run = completedRun();
        CodingRunEvidenceView evidence = evidence(
                List.of("src/main/java/example/LongTask.java"),
                List.of("test"),
                List.of(),
                false,
                false,
                false);
        arrange(run, evidence, List.of(
                event(run.id(), 1, RunEventType.RUN_WAITING_APPROVAL),
                event(run.id(), 2, RunEventType.RUN_RESUMED)));

        CodingEvaluationReportView report = service.evaluate(
                run.id(), CodingEvaluationScenario.LONG_TASK_RECOVERY, ACTOR);

        assertThat(report.score()).isGreaterThanOrEqualTo(80);
        assertThat(report.passed()).isTrue();
        assertThat(report.checks())
                .filteredOn(check -> "recovery-continuity".equals(check.category()))
                .allMatch(CodingEvaluationReportView.CodingEvaluationCheckView::passed);
    }

    @Test
    void refusesReportsForRunsOutsideTheCurrentTenant() {
        when(runs.findByIdAndTenantId("other-run", ACTOR.tenantId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(
                "other-run", CodingEvaluationScenario.EXISTING_REPOSITORY_FEATURE, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Run not found: other-run");
    }

    private void arrange(AgentRunEntity run, CodingRunEvidenceView evidence, List<RunEventEntity> history) {
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));
        when(nodes.codingEvidence(run.id(), ACTOR)).thenReturn(evidence);
        when(events.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(
                run.id(), ACTOR.tenantId(), 0)).thenReturn(history);
    }

    private static AgentRunEntity completedRun() {
        AgentRunEntity run = new AgentRunEntity(
                "run-1", "tenant-1", "user-1", "conversation-1", "model-1", "agent-1", Instant.now());
        run.start();
        run.succeed("已完成：列出修改文件、验证方式和本地地址。");
        return run;
    }

    private static CodingRunEvidenceView evidence(
            List<String> changedFiles,
            List<String> commandVerifications,
            List<String> failedTools,
            boolean browserVerified,
            boolean browserApiVerified,
            boolean managedProcessReady) {
        return evidence(
                changedFiles,
                commandVerifications,
                failedTools,
                browserVerified,
                browserApiVerified,
                managedProcessReady,
                managedProcessReady);
    }

    private static CodingRunEvidenceView evidence(
            List<String> changedFiles,
            List<String> commandVerifications,
            List<String> failedTools,
            boolean browserVerified,
            boolean browserApiVerified,
            boolean managedProcessReady,
            boolean managedProcessReadyAfterLastProjectChange) {
        return new CodingRunEvidenceView(
                "run-1",
                8,
                List.of("fs.write", "shell.run", "git.review"),
                -1,
                changedFiles,
                true,
                changedFiles,
                List.of("shell.run", "browser.verify"),
                commandVerifications,
                browserVerified ? List.of("browser-trace.zip") : List.of(),
                browserVerified,
                false,
                failedTools,
                browserApiVerified,
                false,
                managedProcessReady,
                managedProcessReadyAfterLastProjectChange);
    }

    private static RunEventEntity event(String runId, long sequence, RunEventType type) {
        return new RunEventEntity("tenant-1", runId, sequence, type, "", Instant.now());
    }
}
