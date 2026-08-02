package io.github.yourname.agentstudio.nodeclient.protocol;

import java.time.Instant;
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
        result = result == null ? null : Map.copyOf(result);
        attempt = Math.max(1, attempt);
    }
}
