package io.github.yourname.agentstudio.persona;

import java.time.Instant;
import java.util.Map;

public record UserPersonaView(
        String id,
        String name,
        String description,
        Map<String, Object> attributes,
        boolean defaultPersona,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public UserPersonaView {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
