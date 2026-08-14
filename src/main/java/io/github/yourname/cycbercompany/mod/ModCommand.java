package io.github.yourname.cycbercompany.mod;

import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

public record ModCommand(@NotBlank String command, Map<String, Object> arguments) {
    public ModCommand {
        if (arguments == null || arguments.isEmpty()) {
            arguments = Map.of();
        } else {
            Map<String, Object> normalized = new LinkedHashMap<>();
            arguments.forEach((key, value) -> {
                if (key != null && value != null) {
                    normalized.put(key, value);
                }
            });
            arguments = Map.copyOf(normalized);
        }
    }
}
