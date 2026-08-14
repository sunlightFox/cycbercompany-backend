package io.github.yourname.cycbercompany.node;

import java.time.Instant;

public record NodeRegistrationTokenView(
        String tokenId,
        String registrationToken,
        Instant expiresAt,
        String usageHint) {
}
