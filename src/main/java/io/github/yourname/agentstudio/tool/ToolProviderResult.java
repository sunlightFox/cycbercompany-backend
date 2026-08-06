package io.github.yourname.agentstudio.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider 的结构化结果。
 *
 * <p>Provider 只报告执行状态、结果和错误；结果如何脱敏、限制大小并转换成模型消息，
 * 由上层编排和审计逻辑统一完成。
 */
public record ToolProviderResult(
        String status,
        boolean succeeded,
        Map<String, Object> result,
        String errorMessage,
        String approvalId) {

    public ToolProviderResult {
        status = status == null || status.isBlank() ? (succeeded ? "SUCCEEDED" : "FAILED") : status;
        result = sanitizeResult(result);
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public boolean requiresApproval() {
        return approvalId != null && !approvalId.isBlank();
    }

    private static Map<String, Object> sanitizeResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        result.forEach((key, value) -> {
            if (key != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Collections.unmodifiableMap(sanitized);
    }
}
