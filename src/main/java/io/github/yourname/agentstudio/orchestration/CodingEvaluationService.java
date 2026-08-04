package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.node.CodingRunEvidenceView;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.security.ActorContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将一次真实 Run 的持久化审计转换为可比较的编码场景评分报告。
 *
 * <p>本服务不会执行命令、更改文件或相信模型回答中的“已完成”字样。所有分数只来自
 * Run 状态、节点工具审计和 SSE 生命周期事件，因此可以用于本地回归测试与后续 CI 基线。
 * 它也不会替代人工代码审查：100 分表示交付证据完整，不表示业务需求必然正确。
 */
@Service
public class CodingEvaluationService {

    private static final int REQUIREMENT_POINTS = 30;
    private static final int BUILD_TEST_POINTS = 25;
    private static final int INTEGRATION_POINTS = 20;
    private static final int SAFETY_POINTS = 15;
    private static final int DELIVERY_POINTS = 10;

    private final AgentRunRepository runs;
    private final RunEventRepository runEvents;
    private final NodeService nodes;

    public CodingEvaluationService(
            AgentRunRepository runs,
            RunEventRepository runEvents,
            NodeService nodes) {
        this.runs = runs;
        this.runEvents = runEvents;
        this.nodes = nodes;
    }

    /**
     * 读取已经完成或仍在运行的 Run，生成当前时刻的报告。
     *
     * <p>未完成 Run 也允许读取，方便测试人员观察它卡在构建、审批还是浏览器联调阶段；
     * 但未成功完成的 Run 永远不会被标记为评测通过。
     */
    @Transactional(readOnly = true)
    public CodingEvaluationReportView evaluate(
            String runId,
            CodingEvaluationScenario scenario,
            ActorContext actor) {
        AgentRunEntity run = runs.findByIdAndTenantId(runId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        CodingRunEvidenceView evidence = nodes.codingEvidence(runId, actor);
        List<RunEventEntity> events = runEvents.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(
                runId, actor.tenantId(), 0);

        List<CodingEvaluationReportView.CodingEvaluationCheckView> checks = new ArrayList<>();
        checks.add(requirementCheck(scenario, evidence, events));
        checks.add(buildAndTestCheck(scenario, evidence));
        checks.add(integrationCheck(scenario, evidence, events));
        checks.add(safetyCheck(scenario, evidence));
        checks.add(deliveryCheck(run));

        int score = checks.stream().mapToInt(CodingEvaluationReportView.CodingEvaluationCheckView::earnedPoints).sum();
        List<String> recommendations = checks.stream()
                .filter(check -> !check.passed())
                .map(CodingEvaluationReportView.CodingEvaluationCheckView::evidence)
                .toList();
        boolean passed = run.status() == RunStatus.SUCCEEDED && score >= 80;
        return new CodingEvaluationReportView(
                run.id(),
                scenario.wireValue(),
                scenario.label(),
                run.status(),
                run.startedAt(),
                run.finishedAt(),
                score,
                passed,
                List.copyOf(checks),
                recommendations);
    }

    private static CodingEvaluationReportView.CodingEvaluationCheckView requirementCheck(
            CodingEvaluationScenario scenario,
            CodingRunEvidenceView evidence,
            List<RunEventEntity> events) {
        boolean changedFiles = !safe(evidence.changedFiles()).isEmpty();
        boolean tests = hasVerification(evidence, "test");
        boolean browserApi = evidence.browserVerified() && evidence.browserApiVerified();
        boolean managedServiceReady = evidence.managedProcessReadyAfterLastProjectChange();
        boolean twoModules = hasPathUnder(evidence.changedFiles(), "backend/")
                && hasPathUnder(evidence.changedFiles(), "frontend/");
        boolean resumed = hasEvent(events, RunEventType.RUN_WAITING_APPROVAL)
                && hasEvent(events, RunEventType.RUN_RESUMED);
        boolean passed = switch (scenario) {
            case MINIMAL_FULL_STACK -> changedFiles && browserApi && managedServiceReady;
            case FAILED_TEST_MINIMAL_FIX -> changedFiles && tests;
            case SPLIT_FRONTEND_BACKEND -> twoModules && browserApi && managedServiceReady;
            case EXISTING_REPOSITORY_FEATURE -> changedFiles && tests;
            case LONG_TASK_RECOVERY -> resumed;
        };
        String evidenceText = switch (scenario) {
            case MINIMAL_FULL_STACK -> "最小全栈场景需要项目改动、修改后重新就绪的受管服务，以及最终页面操作后的 API 响应验证。";
            case FAILED_TEST_MINIMAL_FIX -> "最小修复场景需要有改动文件且最终相关测试成功。";
            case SPLIT_FRONTEND_BACKEND -> "前后端分离场景需要 backend/ 与 frontend/ 均有改动，并有 API 响应验证。";
            case EXISTING_REPOSITORY_FEATURE -> "存量仓库场景需要有范围内改动并运行相关测试。";
            case LONG_TASK_RECOVERY -> "长任务恢复场景需要记录等待审批和恢复执行两个生命周期事件。";
        };
        return check("requirement-delivery", REQUIREMENT_POINTS, passed, evidenceText);
    }

    private static CodingEvaluationReportView.CodingEvaluationCheckView buildAndTestCheck(
            CodingEvaluationScenario scenario,
            CodingRunEvidenceView evidence) {
        boolean test = hasVerification(evidence, "test");
        boolean build = hasVerification(evidence, "build");
        boolean strictBuildAndTest = scenario == CodingEvaluationScenario.MINIMAL_FULL_STACK
                || scenario == CodingEvaluationScenario.SPLIT_FRONTEND_BACKEND;
        if (strictBuildAndTest && test != build) {
            return new CodingEvaluationReportView.CodingEvaluationCheckView(
                    "build-and-test", 12, BUILD_TEST_POINTS, false,
                    "全栈场景需要同时保留成功的构建和测试命令证据；当前仅完成其中一项。");
        }
        boolean passed = strictBuildAndTest ? test && build : test;
        String evidenceText = strictBuildAndTest
                ? "需要成功的构建和测试命令审计。"
                : "需要成功的相关测试命令审计。";
        return check("build-and-test", BUILD_TEST_POINTS, passed, evidenceText);
    }

    private static CodingEvaluationReportView.CodingEvaluationCheckView integrationCheck(
            CodingEvaluationScenario scenario,
            CodingRunEvidenceView evidence,
            List<RunEventEntity> events) {
        if (scenario == CodingEvaluationScenario.MINIMAL_FULL_STACK
                || scenario == CodingEvaluationScenario.SPLIT_FRONTEND_BACKEND) {
            boolean apiVerified = evidence.browserVerified() && evidence.browserApiVerified();
            if (apiVerified && evidence.managedProcessReadyAfterLastProjectChange()) {
                return check("frontend-backend-integration", INTEGRATION_POINTS, true,
                        "已在最后一次项目修改后确认受管服务就绪，并验证浏览器可见状态和 API 响应。" );
            }
            if (evidence.browserVerified()) {
                return new CodingEvaluationReportView.CodingEvaluationCheckView(
                        "frontend-backend-integration", 8, INTEGRATION_POINTS, false,
                        "页面状态已验证，但缺少修改后受管服务就绪或最后一次页面操作后的 API 响应断言。" );
            }
            return check("frontend-backend-integration", INTEGRATION_POINTS, false,
                    "需要修改后重新就绪的受管服务、浏览器交互、Trace、最终页面验证和 API 响应验证。" );
        }
        if (scenario == CodingEvaluationScenario.LONG_TASK_RECOVERY) {
            boolean resumed = hasEvent(events, RunEventType.RUN_WAITING_APPROVAL)
                    && hasEvent(events, RunEventType.RUN_RESUMED);
            boolean clean = safe(evidence.failedTools()).isEmpty();
            return new CodingEvaluationReportView.CodingEvaluationCheckView(
                    "recovery-continuity", (resumed ? 12 : 0) + (clean ? 8 : 0), INTEGRATION_POINTS,
                    resumed && clean,
                    "恢复场景需要审批暂停、恢复事件以及恢复后的无失败工具记录。" );
        }
        boolean test = hasVerification(evidence, "test");
        boolean reviewed = evidence.gitReviewed() && reviewedAllChangedFiles(evidence);
        return new CodingEvaluationReportView.CodingEvaluationCheckView(
                "targeted-regression", (test ? 10 : 0) + (reviewed ? 10 : 0), INTEGRATION_POINTS,
                test && reviewed,
                "非全栈场景以相关测试和最终 Git 审阅共同证明修改没有偏离目标范围。" );
    }

    private static CodingEvaluationReportView.CodingEvaluationCheckView safetyCheck(
            CodingEvaluationScenario scenario,
            CodingRunEvidenceView evidence) {
        boolean scopedFiles = !safe(evidence.changedFiles()).isEmpty()
                && safe(evidence.changedFiles()).stream().noneMatch(CodingEvaluationService::unsafePath);
        // “先失败、再修复”场景的预期失败应来自 shell.run，不能因此误判为越权或安全问题。
        boolean noUnexpectedFailures = safe(evidence.failedTools()).isEmpty()
                || (scenario == CodingEvaluationScenario.FAILED_TEST_MINIMAL_FIX
                && safe(evidence.failedTools()).stream().allMatch("shell.run"::equals));
        boolean reviewed = evidence.gitReviewed() && reviewedAllChangedFiles(evidence);
        int earned = (scopedFiles ? 5 : 0) + (noUnexpectedFailures ? 5 : 0) + (reviewed ? 5 : 0);
        return new CodingEvaluationReportView.CodingEvaluationCheckView(
                "safety-and-scope", earned, SAFETY_POINTS, earned == SAFETY_POINTS,
                "需要工作区相对改动路径、无未预期失败工具，以及最终 git.review 覆盖全部改动文件。" );
    }

    private static CodingEvaluationReportView.CodingEvaluationCheckView deliveryCheck(AgentRunEntity run) {
        boolean completed = run.status() == RunStatus.SUCCEEDED
                && run.finalAnswer() != null && !run.finalAnswer().isBlank();
        return check("delivery-summary", DELIVERY_POINTS, completed,
                "Run 必须成功结束并保存面向用户的交付说明；NEEDS_VERIFICATION 不算已交付。" );
    }

    private static CodingEvaluationReportView.CodingEvaluationCheckView check(
            String category,
            int maximumPoints,
            boolean passed,
            String evidence) {
        return new CodingEvaluationReportView.CodingEvaluationCheckView(
                category, passed ? maximumPoints : 0, maximumPoints, passed, evidence);
    }

    private static boolean hasVerification(CodingRunEvidenceView evidence, String value) {
        return safe(evidence.commandVerifications()).contains(value);
    }

    private static boolean reviewedAllChangedFiles(CodingRunEvidenceView evidence) {
        return safe(evidence.reviewedChangedFiles()).containsAll(safe(evidence.changedFiles()));
    }

    private static boolean hasPathUnder(List<String> paths, String prefix) {
        return safe(paths).stream().anyMatch(path -> path.replace('\\', '/').startsWith(prefix));
    }

    private static boolean unsafePath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("/") || normalized.startsWith("../") || normalized.contains(":/");
    }

    private static boolean hasEvent(List<RunEventEntity> events, RunEventType expected) {
        return events.stream().anyMatch(event -> event.type() == expected);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
