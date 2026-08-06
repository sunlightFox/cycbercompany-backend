package io.github.yourname.agentstudio.node;

/** 节点工具调用的审计状态。 */
public enum NodeToolInvocationStatus {
    /** 后端已创建调用记录。 */
    REQUESTED,
    /** 调用已通过 WebSocket 发给节点。 */
    DISPATCHED,
    /** 节点已写入本地 journal 并确认收到。 */
    ACCEPTED,
    /** 节点正在执行。 */
    RUNNING,
    /** 节点确认成功。 */
    SUCCEEDED,
    /** 节点确认失败。 */
    FAILED,
    /** 后端或节点等待超时。 */
    TIMED_OUT,
    /** 结果无法确认，必须人工或 journal 对账，不能自动重放副作用。 */
    UNKNOWN,
    /** 调用被取消。 */
    CANCELLED,
    /** 调用未执行，正在等待审批。 */
    APPROVAL_REQUIRED
}
