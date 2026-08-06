package io.github.yourname.agentstudio.nodeclient.protocol;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 节点在执行副作用前持久化的最小调用事实。 */
public record NodeJournalEntry(
        String invocationId,
        String toolName,
        String argumentsDigest,
        int attempt,
        String status,
        Map<String, Object> result,
        String errorMessage,
        Instant acceptedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {

    public boolean terminal() {
        return "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "CANCELLED".equals(status)
                || "TIMED_OUT".equals(status)
                || "UNKNOWN".equals(status);
    }

    public NodeJournalEntry {
        result = sanitizeResult(result);
        attempt = Math.max(1, attempt);
    }

    private static Map<String, Object> sanitizeResult(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        if (result.isEmpty()) {
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
