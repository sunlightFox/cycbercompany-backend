package io.github.yourname.agentstudio.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record NodeToolCallResult(
        String invocationId,
        String nodeId,
        String toolName,
        String status,
        Map<String, Object> result,
        String errorMessage) {

    public NodeToolCallResult {
        result = sanitizeResult(result);
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
