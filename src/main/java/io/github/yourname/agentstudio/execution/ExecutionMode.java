package io.github.yourname.agentstudio.execution;

/** 定义当前安装允许在哪些电脑上执行本机工具。 */
public enum ExecutionMode {
    /** 只使用自动管理的本地执行器。 */
    PERSONAL_LOCAL,
    /** 同时允许本地执行器和显式选择的注册节点。 */
    LOCAL_AND_NODES,
    /** 只使用注册节点或受信沙箱节点。 */
    NODES_ONLY;

    /** 是否允许启动本机托管执行器。 */
    public boolean usesManagedLocalExecutor() {
        return this != NODES_ONLY;
    }

    /** 是否在界面和 API 中暴露注册节点。 */
    public boolean exposesRegisteredNodes() {
        return this != PERSONAL_LOCAL;
    }
}
