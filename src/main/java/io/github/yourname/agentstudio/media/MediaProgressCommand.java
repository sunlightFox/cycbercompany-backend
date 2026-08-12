package io.github.yourname.agentstudio.media;

import jakarta.validation.constraints.NotBlank;

public record MediaProgressCommand(
        @NotBlank String modId,
        @NotBlank String mediaId,
        String sourceId,
        String episodeId,
        Long positionMs,
        Long durationMs,
        Boolean completed) {
}
