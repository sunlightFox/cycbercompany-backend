package io.github.yourname.agentstudio.orchestration;

/**
 * Agent 编排检查点的阶段。
 *
 * <p>阶段信息并不替代 {@link RunStatus}：RunStatus 是面向用户的业务结果，
 * 此枚举用于说明运行在规划、执行、等待审批还是验证的哪个步骤停下。
 */
public enum RunWorkflowPhase {
    QUEUED,
    INSPECTING,
    EXECUTING,
    WAITING_APPROVAL,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}
