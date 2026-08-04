package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证结构化编码计划只会被真实工具类别推进，并能在 JSON 中安全恢复。 */
class CodingWorkflowPlanTest {

    private static final Instant T0 = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void completesAChangedProjectOnlyAfterPostChangeVerificationAndReview() throws Exception {
        CodingWorkflowPlan plan = CodingWorkflowPlan.initial()
                .afterToolResult("project.map", true, T0.plusSeconds(1))
                .afterToolResult("fs.write", true, T0.plusSeconds(2))
                .afterToolResult("shell.run", true, T0.plusSeconds(3))
                .afterToolResult("git.review", true, T0.plusSeconds(4));

        assertThat(plan.deliveryBlockers()).isEmpty();
        CodingWorkflowPlan delivered = plan.afterDeliveryEvidence(true, true, T0.plusSeconds(5));

        assertThat(delivered.steps()).extracting(CodingWorkflowStepState::status)
                .containsExactly(
                        CodingWorkflowStepStatus.COMPLETED,
                        CodingWorkflowStepStatus.COMPLETED,
                        CodingWorkflowStepStatus.COMPLETED,
                        CodingWorkflowStepStatus.COMPLETED,
                        CodingWorkflowStepStatus.COMPLETED,
                        CodingWorkflowStepStatus.COMPLETED);
        assertThat(delivered.resumeGuidance()).doesNotContain("fs.write", "shell.run");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CodingWorkflowPlan restored = mapper.readValue(mapper.writeValueAsString(delivered), CodingWorkflowPlan.class);
        assertThat(restored).isEqualTo(delivered);
    }

    @Test
    void aNewChangeExpiresEarlierVerificationAndReviewEvidence() {
        CodingWorkflowPlan plan = CodingWorkflowPlan.initial()
                .afterToolResult("fs.read", true, T0.plusSeconds(1))
                .afterToolResult("fs.write", true, T0.plusSeconds(2))
                .afterToolResult("shell.run", true, T0.plusSeconds(3))
                .afterToolResult("git.review", true, T0.plusSeconds(4))
                .afterToolResult("fs.apply_patch", true, T0.plusSeconds(5));

        assertThat(plan.state(CodingWorkflowStep.VERIFY).status())
                .isEqualTo(CodingWorkflowStepStatus.PENDING);
        assertThat(plan.state(CodingWorkflowStep.REVIEW).status())
                .isEqualTo(CodingWorkflowStepStatus.PENDING);
    }

    @Test
    void readingAChangedFileCountsAsPostChangeReview() {
        CodingWorkflowPlan plan = CodingWorkflowPlan.initial()
                .afterToolResult("fs.write", true, T0.plusSeconds(1))
                .afterToolResult("fs.read", true, T0.plusSeconds(2));

        assertThat(plan.state(CodingWorkflowStep.INSPECT).status())
                .isEqualTo(CodingWorkflowStepStatus.COMPLETED);
        assertThat(plan.state(CodingWorkflowStep.REVIEW).status())
                .isEqualTo(CodingWorkflowStepStatus.COMPLETED);
    }

    @Test
    void systemPrefixedNodeToolsAdvanceTheSameWorkflowSteps() {
        CodingWorkflowPlan plan = CodingWorkflowPlan.initial()
                .afterToolResult("system.fs.list", true, T0.plusSeconds(1))
                .afterToolResult("system.fs.write", true, T0.plusSeconds(2))
                .afterToolResult("system.shell.run", true, T0.plusSeconds(3))
                .afterToolResult("system.fs.read", true, T0.plusSeconds(4));

        assertThat(plan.deliveryBlockers()).isEmpty();
    }

    @Test
    void changingBeforeInspectionLeavesAnExplicitRecoveryReason() {
        CodingWorkflowPlan plan = CodingWorkflowPlan.initial()
                .afterToolResult("fs.write", true, T0.plusSeconds(1));

        assertThat(plan.deliveryBlockers()).anyMatch(reason -> reason.contains("inspect"));
        assertThat(plan.resumeGuidance()).contains("修改前未记录到项目检查");
    }

    @Test
    void failedToolOnlyStoresAGenericRecoverySummary() {
        CodingWorkflowPlan plan = CodingWorkflowPlan.initial()
                .afterToolResult("shell.run", false, T0.plusSeconds(1));

        assertThat(plan.resumeGuidance()).contains("需在恢复后重新检查")
                .doesNotContain("secret", "C:\\", "--token");
    }
}
