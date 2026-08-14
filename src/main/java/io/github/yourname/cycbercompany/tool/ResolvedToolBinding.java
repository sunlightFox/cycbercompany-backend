package io.github.yourname.cycbercompany.tool;

import java.util.LinkedHashMap;
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
        inputSchema = sanitizeObjectMap(inputSchema);
        attributes = sanitizeStringMap(attributes);
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

    private static Map<String, Object> sanitizeObjectMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    private static Map<String, String> sanitizeStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }
}
