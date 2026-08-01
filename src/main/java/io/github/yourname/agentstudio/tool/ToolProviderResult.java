package io.github.yourname.agentstudio.tool;

import java.util.Map;

/** Provider 的结构化结果；序列化、上下文预算和模型适配由上层统一完成。 */
public record ToolProviderResult(
        String status,
        boolean succeeded,
        Map<String, Object> result,
        String errorMessage,
        String approvalId) {

    public ToolProviderResult {
        status = status == null || status.isBlank() ? (succeeded ? "SUCCEEDED" : "FAILED") : status;
        result = result == null ? Map.of() : Map.copyOf(result);
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public boolean requiresApproval() {
        return approvalId != null && !approvalId.isBlank();
    }
}
