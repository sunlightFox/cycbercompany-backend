package io.github.yourname.cycbercompany.tool;

import java.util.List;

public record WebSearchResponse(
        String query,
        WebSearchMode intent,
        List<WebSearchResult> results,
        WebSearchTrace trace) {
}
