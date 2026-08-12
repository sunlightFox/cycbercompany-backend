package io.github.yourname.agentstudio.media;

import jakarta.validation.constraints.NotBlank;

public record MediaResolveCommand(
        @NotBlank String mediaId,
        String sourceId,
        String episodeId) {
}
