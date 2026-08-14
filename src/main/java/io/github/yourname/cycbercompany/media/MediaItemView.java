package io.github.yourname.cycbercompany.media;

/** A normalized media result returned by a source adapter. */
public record MediaItemView(
        String id,
        String title,
        String type,
        String sourceKey,
        String sourceName,
        String posterUrl,
        boolean playable,
        String availability,
        String accessMode,
        String authProvider) {
    public MediaItemView(String id, String title, String type, String sourceKey, String sourceName,
                         String posterUrl, boolean playable, String availability) {
        this(id, title, type, sourceKey, sourceName, posterUrl, playable, availability, "UNKNOWN", null);
    }
}
