package io.github.yourname.agentstudio.node;

import java.util.List;

/**
 * 一次“只读状态对账”请求的安全摘要。
 *
 * <p>此对象刻意不包含工具参数、工具结果、参数摘要、节点密钥或会话令牌。前端只需要知道：
 * 哪些历史命令已向在线节点发出状态查询，以及哪些节点当前不可用。
 */
public record RunNodeReconciliationView(
        String runId,
        int statusRequested,
        int nodeUnavailable,
        List<Invocation> invocations) {

    /** 单条命令的对账投递结果，而不是节点实际工具执行结果。 */
    public record Invocation(
            String invocationId,
            String nodeId,
            String toolName,
            NodeToolInvocationStatus currentStatus,
            Outcome outcome) {
    }

    /**
     * STATUS_REQUESTED 表示查询帧已交给当前 WebSocket；最终状态仍须等待节点的 tool.status.result。
     * NODE_UNAVAILABLE 表示未发送查询，数据库里的原状态不会被这个接口擅自修改。
     */
    public enum Outcome {
        STATUS_REQUESTED,
        NODE_UNAVAILABLE
    }
}
