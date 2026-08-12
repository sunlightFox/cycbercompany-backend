package io.github.yourname.agentstudio.media;

import java.util.List;

public record MediaSearchView(
        String query,
        String status,
        String message,
        List<MediaItemView> items,
        List<String> sourceKeys) {
}
