package io.github.yourname.cycbercompany.orchestration;

/** 一次 Agent Run 的生命周期状态。 */
public enum RunStatus {
    /** 已保存并等待会话队列调度。 */
    QUEUED,
    /** 兼容旧数据的创建态；新流程通常直接进入 QUEUED。 */
    CREATED,
    /** worker 正在执行模型调用或工具循环。 */
    RUNNING,
    /** 工具调用需要人工审批，当前 Run 保留队列槽位。 */
    WAITING_APPROVAL,
    /** 已成功生成最终回答。 */
    SUCCEEDED,
    /** 已生成结果，但缺少交付门禁要求的验证证据。 */
    NEEDS_VERIFICATION,
    /** 执行失败。 */
    FAILED,
    /** 用户取消。 */
    CANCELLED,
    /** 超时结束。 */
    TIMED_OUT
}
