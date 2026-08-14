package io.github.yourname.cycbercompany.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider 暴露给控制面的规范化工具描述。
 *
 * @param bindingId 全局唯一且稳定的绑定 ID，例如 {@code node:node-1:fs.read}
 * @param logicalName Skill、Agent 和 Run 使用的逻辑名称，例如 {@code fs.read}
 * @param providerId 执行 Provider 的固定 ID；模型不能修改
 * @param providerToolName Provider 内部使用的真实工具名
 * @param attributes 路由所需的非敏感固定属性，例如 MCP connectionId
 */
public record ToolDescriptor(
        String bindingId,
        String logicalName,
        String providerId,
        String providerToolName,
        String description,
        RiskLevel riskLevel,
        boolean requiresApproval,
        Map<String, Object> inputSchema,
        Map<String, String> attributes) {

    public ToolDescriptor {
        bindingId = requireText(bindingId, "bindingId");
        logicalName = requireText(logicalName, "logicalName");
        providerId = requireText(providerId, "providerId");
        providerToolName = requireText(providerToolName, "providerToolName");
        description = description == null ? "" : description;
        riskLevel = riskLevel == null ? RiskLevel.HIGH : riskLevel;
        inputSchema = sanitizeObjectMap(inputSchema);
        attributes = sanitizeStringMap(attributes);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tool descriptor " + field + " must not be blank.");
        }
        return value;
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
