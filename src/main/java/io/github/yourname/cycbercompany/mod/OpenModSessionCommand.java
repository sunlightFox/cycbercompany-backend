package io.github.yourname.cycbercompany.mod;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record OpenModSessionCommand(
        @NotBlank String modId,
        String surfaceId,
        String presentation,
        @JsonAlias("mediaId") String resourceId,
        String title) {
}
