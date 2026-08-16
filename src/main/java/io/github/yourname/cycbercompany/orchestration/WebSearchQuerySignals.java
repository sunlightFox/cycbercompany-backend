package io.github.yourname.cycbercompany.orchestration;

import java.util.List;
import java.util.Locale;

/** Small shared query signals for final-answer source delivery. */
final class WebSearchQuerySignals {

    private WebSearchQuerySignals() {
    }

    static boolean primarySourceRequested(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return normalized.contains("github") || normalized.contains("gitlab")
                || normalized.contains("open source") || normalized.contains("official")
                || normalized.contains("repository") || normalized.contains(" repo")
                || normalized.contains("\u5f00\u6e90") || normalized.contains("\u5b98\u7f51")
                || normalized.contains("\u5b98\u65b9") || normalized.contains("\u4ec0\u4e48\u662f")
                || normalized.contains("\u662f\u4ec0\u4e48");
    }

    static List<String> vendorTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .stream().filter(token -> token.length() >= 4)
                .filter(token -> !List.of("github", "gitlab", "official", "source", "repository", "harness").contains(token))
                .distinct().toList();
    }
}
