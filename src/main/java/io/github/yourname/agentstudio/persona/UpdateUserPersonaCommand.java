package io.github.yourname.agentstudio.persona;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record UpdateUserPersonaCommand(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @Size(max = 40) Map<String, Object> attributes,
        @NotNull @PositiveOrZero Long expectedRevision) {
}
