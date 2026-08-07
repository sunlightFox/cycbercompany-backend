package io.github.yourname.agentstudio.persona;

public record UserPersonaContext(
        String id,
        String name,
        String description,
        String attributesJson) {
}
