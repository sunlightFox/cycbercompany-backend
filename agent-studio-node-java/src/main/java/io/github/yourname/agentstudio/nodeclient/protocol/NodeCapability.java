package io.github.yourname.agentstudio.nodeclient.protocol;

import java.util.Map;

/**
 * 节点上报的本机执行能力。
 *
 * <p>风险等级、是否启用和审批规则不再属于节点协议，它们由服务端保存并执行。
 * 这样即使客户端版本过旧或被篡改，也不能改变服务端的权限判断。
 */
public record NodeCapability(
        String name,
        String description,
        String version,
        Map<String, Object> inputSchema) {

    public NodeCapability(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, "1", inputSchema);
    }

    /**
     * 兼容尚未完成精简的本地工具注册代码。后三个参数不会序列化到服务端，
     * 也不会参与任何权限决策，应逐步改为三参数构造器。
     */
    public NodeCapability(
            String name,
            String description,
            String ignoredRiskLevel,
            Boolean ignoredEnabled,
            Boolean ignoredRequiresApproval,
            Map<String, Object> inputSchema) {
        this(name, description, "1", inputSchema);
    }
}
