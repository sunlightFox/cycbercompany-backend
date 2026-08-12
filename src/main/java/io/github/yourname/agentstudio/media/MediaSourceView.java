package io.github.yourname.agentstudio.media;

public record MediaSourceView(
        String key,
        String name,
        String adapter,
        boolean searchable,
        boolean quickSearch,
        boolean requiresIsolatedRuntime,
        String status,
        String websiteUrl,
        String accessMode) {
    /** Compatibility constructor for sources without an explicit website endpoint. */
    public MediaSourceView(String key, String name, String adapter, boolean searchable, boolean quickSearch,
                           boolean requiresIsolatedRuntime, String status) {
        this(key, name, adapter, searchable, quickSearch, requiresIsolatedRuntime, status, null, "UNKNOWN");
    }

    public MediaSourceView(String key, String name, String adapter, boolean searchable, boolean quickSearch,
                           boolean requiresIsolatedRuntime, String status, String websiteUrl) {
        this(key, name, adapter, searchable, quickSearch, requiresIsolatedRuntime, status, websiteUrl, "UNKNOWN");
    }
}
