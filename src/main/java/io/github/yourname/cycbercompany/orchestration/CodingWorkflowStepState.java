package io.github.yourname.cycbercompany.orchestration;

import java.time.Instant;

/**
 * 单个步骤的最小可恢复状态。
 *
 * <p>failureSummary 只能是宿主生成的泛化说明，不能写入节点返回的异常文本。节点异常中可能包含命令、
 * 工作目录、令牌或网页内容，原始信息仍应留在受权限保护的调用审计中。
 */
public record CodingWorkflowStepState(
        CodingWorkflowStep step,
        CodingWorkflowStepStatus status,
        Instant startedAt,
        Instant completedAt,
        String failureSummary) {

    public CodingWorkflowStepState {
        if (step == null || status == null) {
            throw new IllegalArgumentException("Coding workflow step and status are required.");
        }
        failureSummary = bounded(failureSummary);
    }

    static CodingWorkflowStepState pending(CodingWorkflowStep step) {
        return new CodingWorkflowStepState(step, CodingWorkflowStepStatus.PENDING, null, null, null);
    }

    CodingWorkflowStepState completed(Instant now) {
        Instant started = startedAt == null ? now : startedAt;
        return new CodingWorkflowStepState(step, CodingWorkflowStepStatus.COMPLETED, started, now, null);
    }

    CodingWorkflowStepState inProgress(Instant now) {
        if (status == CodingWorkflowStepStatus.COMPLETED || status == CodingWorkflowStepStatus.NOT_REQUIRED) {
            return this;
        }
        return new CodingWorkflowStepState(step, CodingWorkflowStepStatus.IN_PROGRESS,
                startedAt == null ? now : startedAt, null, null);
    }

    CodingWorkflowStepState blocked(String summary, Instant now) {
        return new CodingWorkflowStepState(step, CodingWorkflowStepStatus.BLOCKED,
                startedAt == null ? now : startedAt, null, summary);
    }

    CodingWorkflowStepState notRequired(Instant now) {
        return new CodingWorkflowStepState(step, CodingWorkflowStepStatus.NOT_REQUIRED,
                startedAt == null ? now : startedAt, now, null);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.strip();
        return compact.length() <= 240 ? compact : compact.substring(0, 240);
    }
}
