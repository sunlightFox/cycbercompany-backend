package io.github.yourname.agentstudio.node;

import java.util.Map;

public record NodeToolCallResult(
        String invocationId,
        String nodeId,
        String toolName,
        String status,
        Map<String, Object> result,
        String errorMessage) {
}
