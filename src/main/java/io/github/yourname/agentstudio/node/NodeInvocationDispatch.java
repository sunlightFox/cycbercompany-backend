package io.github.yourname.agentstudio.node;

import java.time.Instant;
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
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
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
}
