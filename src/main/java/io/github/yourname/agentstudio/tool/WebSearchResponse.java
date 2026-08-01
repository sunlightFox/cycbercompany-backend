package io.github.yourname.agentstudio.tool;

import java.util.List;

public record WebSearchResponse(
        String query,
        WebSearchMode intent,
        List<WebSearchResult> results,
        WebSearchTrace trace) {
}
