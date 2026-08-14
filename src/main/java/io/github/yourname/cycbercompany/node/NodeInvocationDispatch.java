package io.github.yourname.cycbercompany.node;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端已经持久化、可以下发给节点的一次调用快照。 */
public record NodeInvocationDispatch(
        String invocationId,
        String runId,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        String workspaceRef,
        String executionSessionId,
        Instant deadlineAt,
        String policyRevision,
        String argumentsDigest,
        int attempt,
        String idempotencyKey,
        String traceId) {

    public NodeInvocationDispatch {
        arguments = sanitizeArguments(arguments);
        attempt = Math.max(1, attempt);
    }

    public Map<String, Object> payload() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("invocationId", invocationId);
        payload.put("runId", runId);
        payload.put("toolCallId", toolCallId);
        payload.put("toolName", toolName);
        payload.put("arguments", arguments);
        payload.put("workspaceRef", workspaceRef);
        payload.put("executionSessionId", executionSessionId);
        payload.put("deadlineAt", deadlineAt == null ? null : deadlineAt.toString());
        payload.put("policyRevision", policyRevision);
        payload.put("argumentsDigest", argumentsDigest);
        payload.put("attempt", attempt);
        payload.put("idempotencyKey", idempotencyKey);
        return payload;
    }

    private static Map<String, Object> sanitizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }
}
