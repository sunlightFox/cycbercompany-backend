package io.github.yourname.agentstudio.security;

import java.util.Set;

/**
 * Trusted identity object passed into domain services.
 *
 * <p>Controllers may build this from headers in local mode, while production
 * can build it from JWT/OIDC claims. Domain code never trusts tenant/user data
 * supplied by prompts, tool arguments, or model output.
 */
public record ActorContext(
        String tenantId,
        String userId,
        Set<String> roles,
        Set<String> scopes) {

    public static ActorContext local() {
        return new ActorContext("local", "local-user", Set.of("LOCAL_USER"), Set.of("agent:run"));
    }
}
