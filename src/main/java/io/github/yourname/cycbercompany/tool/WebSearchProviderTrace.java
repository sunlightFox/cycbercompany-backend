package io.github.yourname.cycbercompany.tool;

public record WebSearchProviderTrace(
        String sourceId,
        String status,
        String query,
        int resultCount,
        long durationMs,
        String detail) {
}
