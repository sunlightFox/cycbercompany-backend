package io.github.yourname.agentstudio.tool;

import java.util.Map;

/**
 * Run 准备阶段固定的工具绑定。
 *
 * <p>{@code modelName} 是本次 Run 交给模型的函数名；真正路由只认 bindingId/providerId，
 * 因此模型参数里伪造 provider 或 nodeId 不能改变执行目标。
 */
public record ResolvedToolBinding(
        String bindingId,
        String modelName,
        String logicalName,
        String providerId,
        String providerToolName,
        String description,
        RiskLevel riskLevel,
        boolean requiresApproval,
        Map<String, Object> inputSchema,
        Map<String, String> attributes) {

    public ResolvedToolBinding {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    static ResolvedToolBinding from(ToolDescriptor descriptor, String modelName) {
        return new ResolvedToolBinding(
                descriptor.bindingId(),
                modelName,
                descriptor.logicalName(),
                descriptor.providerId(),
                descriptor.providerToolName(),
                descriptor.description(),
                descriptor.riskLevel(),
                descriptor.requiresApproval(),
                descriptor.inputSchema(),
                descriptor.attributes());
    }
}
