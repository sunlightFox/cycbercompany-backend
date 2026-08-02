package io.github.yourname.agentstudio.tool;

/** Normalizes provider-controlled labels before they enter model-visible tool metadata. */
public final class ModelVisibleText {

    private ModelVisibleText() {
    }

    public static String oneLine(String value, String fallback, int maxCharacters) {
        String normalized = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            normalized = fallback == null ? "" : fallback.trim();
        }
        int limit = Math.max(1, maxCharacters);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
