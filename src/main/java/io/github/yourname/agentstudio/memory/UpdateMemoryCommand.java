package io.github.yourname.agentstudio.memory;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record UpdateMemoryCommand(
        @NotNull MemoryType type,
        @NotBlank @Size(max = 4000) String content,
        @DecimalMin("0.0") @DecimalMax("1.0") Double importance,
        Instant expiresAt,
        @NotNull @PositiveOrZero Long expectedRevision) {
}
