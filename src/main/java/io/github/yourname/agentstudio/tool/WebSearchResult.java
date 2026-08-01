package io.github.yourname.agentstudio.tool;

import java.time.Instant;

public record WebSearchResult(
        String title,
        String url,
        String snippet,
        String sourceId,
        String sourceType,
        Instant publishedAt,
        WebEvidence evidence) {

    public WebSearchResult(String title, String url, String snippet) {
        this(title, url, snippet, "unknown", "unknown", null, null);
    }

    public WebSearchResult(String title, String url, String snippet, String sourceId, String sourceType, Instant publishedAt) {
        this(title, url, snippet, sourceId, sourceType, publishedAt, null);
    }
}
