package io.github.yourname.cycbercompany.persona;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateUserPersonaCommand(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @Size(max = 40) Map<String, Object> attributes,
        Boolean defaultPersona) {
}
