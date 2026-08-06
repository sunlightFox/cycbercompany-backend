package io.github.yourname.agentstudio.node;

/** 后端看到的节点连接状态。 */
public enum NodeStatus {
    /** WebSocket 在线，且节点启用。 */
    ONLINE,
    /** 节点启用但当前没有有效连接。 */
    OFFLINE,
    /** 管理员禁用，不能执行工具。 */
    DISABLED
}
