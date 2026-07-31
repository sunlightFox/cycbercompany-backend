package io.github.yourname.agentstudio.node;

import jakarta.validation.constraints.NotBlank;

public record RegisterNodeCommand(
        @NotBlank String registrationToken,
        String name,
        String hostname,
        String osName,
        String osArch,
        String clientVersion) {
}
