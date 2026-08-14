package io.github.yourname.cycbercompany.agent;

import java.util.List;

public class AgentManifestValidationException extends IllegalArgumentException {

    private final List<String> errors;

    public AgentManifestValidationException(List<String> errors) {
        super("Agent manifest validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
