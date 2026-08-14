package io.github.yourname.cycbercompany.node;

import java.util.Map;

/**
 * 节点向服务端报告的“执行能力清单”。
 *
 * <p>此协议刻意不包含风险、启用状态和审批要求。这些权限决策属于服务端策略目录，
 * 节点只说明本机能否识别并执行该命令，以及命令需要哪些参数。
 */
public record NodeCapabilityPayload(
        String name,
        String description,
        String version,
        Map<String, Object> inputSchema) {

    public NodeCapabilityPayload(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, "1", inputSchema);
    }
}
