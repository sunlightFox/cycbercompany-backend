package io.github.yourname.cycbercompany.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WebSearchCommand(
        @NotBlank String query,
        Integer limit,
        WebSearchMode mode,
        WebSearchFreshness freshness,
        @Size(max = 20) List<String> includeDomains,
        @Size(max = 20) List<String> excludeDomains,
        Boolean trace) {

    public WebSearchCommand(String query, Integer limit) {
        this(query, limit, WebSearchMode.AUTO, WebSearchFreshness.ANY, List.of(), List.of(), false);
    }
}
