package io.github.yourname.cycbercompany.orchestration;

/**
 * Run 执行任务的持久状态。
 *
 * <p>它和 {@link RunStatus} 分开保存：Run 描述用户看到的业务结果，任务状态描述
 * worker 是否可以领取、是否持有租约，以及是否能在服务重启后安全恢复。
 */
public enum RunExecutionTaskStatus {
    /** 已持久化，等待本地队列或 worker 领取。 */
    READY,
    /** 某个 worker 正在执行，并且持有有限时间的 lease。 */
    RUNNING,
    /** 模型循环因等待人工审批暂停，不应由恢复扫描自动重放。 */
    WAITING_APPROVAL,
    SUCCEEDED,
    NEEDS_VERIFICATION,
    FAILED,
    CANCELLED,
    /** 进程在副作用尚无法对账时退出，必须由后续流程确认，不能自动重放。 */
    UNKNOWN;

    public boolean terminal() {
        return this == SUCCEEDED
                || this == NEEDS_VERIFICATION
                || this == FAILED
                || this == CANCELLED
                || this == UNKNOWN;
    }
}
