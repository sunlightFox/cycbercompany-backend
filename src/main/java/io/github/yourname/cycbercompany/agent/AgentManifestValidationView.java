package io.github.yourname.cycbercompany.agent;

import java.util.List;

public record AgentManifestValidationView(
        boolean valid,
        List<String> errors,
        String manifestDigest,
        String compiledPromptDigest) {

    public AgentManifestValidationView {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
