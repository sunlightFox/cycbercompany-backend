package io.github.yourname.agentstudio.node;

/** 节点来源和调度语义。 */
public enum NodeKind {
    /** 当前个人本地安装自动创建的执行器。 */
    MANAGED_LOCAL,
    /** 用户注册的个人电脑；不会从多设备池中自动选择。 */
    REGISTERED,
    /**
     * 管理员指定的非个人执行沙箱。只有这种节点可以按标签/能力自动路由；
     * 个人电脑仍然需要显式选择。
     */
    SANDBOX
}
