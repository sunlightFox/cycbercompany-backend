package io.github.yourname.agentstudio.orchestration;

/**
 * SSE 推送给前端的 Run 事件类型。
 *
 * <p>这些值会持久化到 run_event 表。新增枚举时要考虑旧本地 H2 数据库兼容性，
 * 所以 {@link RunEventEntity} 使用字符串保存事件名，而不是数据库 enum check。
 */
public enum RunEventType {
    /** Run 已进入会话队列。 */
    RUN_QUEUED,
    /** Skill 已解析并锁定版本。 */
    SKILLS_RESOLVED,
    /** RunSpec 已生成并计算摘要。 */
    RUN_SPEC_RESOLVED,
    /** Run 开始执行。 */
    RUN_STARTED,
    /** Safe user-facing progress summary; never contains hidden model reasoning. */
    PROGRESS_UPDATE,
    /** 编排步骤开始。 */
    STEP_STARTED,
    /** 知识库或网页检索完成。 */
    RETRIEVAL_COMPLETED,
    /** 回答中实际引用的检索来源。 */
    RETRIEVAL_SOURCES,
    /** 模型触发限流。 */
    MODEL_RATE_LIMITED,
    /** 模型网关准备重试供应商瞬时错误。 */
    MODEL_PROVIDER_RETRYING,
    /** 模型请求调用工具。 */
    TOOL_CALL_REQUESTED,
    /** 工具实际开始执行。 */
    TOOL_CALL_STARTED,
    TOOL_CALL_PROGRESS,
    /** 资源清理开始，例如停止托管进程。 */
    RESOURCE_CLEANUP_STARTED,
    /** 工具需要人工审批。 */
    TOOL_APPROVAL_REQUIRED,
    /** 工具执行完成。 */
    TOOL_CALL_COMPLETED,
    /** 工具结果接近或超过上下文预算。 */
    TOOL_BUDGET_WARNING,
    /** Run 因审批进入暂停态。 */
    RUN_WAITING_APPROVAL,
    /** 审批完成后恢复执行。 */
    RUN_RESUMED,
    /** 工具执行失败。 */
    TOOL_CALL_FAILED,
    /** 清理资源时出现非致命问题。 */
    RESOURCE_CLEANUP_WARNING,
    /** 模型流式文本增量。 */
    TOKEN_DELTA,
    /** 一个编排步骤完成。 */
    STEP_COMPLETED,
    /** 交付门禁要求更多验证证据。 */
    RUN_NEEDS_VERIFICATION,
    /** 最终回答。 */
    FINAL_ANSWER,
    /** Run 失败。 */
    RUN_FAILED,
    /** Run 被取消。 */
    RUN_CANCELLED
}
