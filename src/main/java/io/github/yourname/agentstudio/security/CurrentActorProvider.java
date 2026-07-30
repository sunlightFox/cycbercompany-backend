package io.github.yourname.agentstudio.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Converts request metadata into a trusted ActorContext for the local profile.
 *
 * <p>The X-* headers are intentionally only a development convenience. They
 * make tenant filtering observable in tests and demos, while leaving one clear
 * class to replace when real authentication is enabled.
 */
@Component
public class CurrentActorProvider {

    public ActorContext current(HttpServletRequest request) {
        String tenantId = headerOrDefault(request, "X-Tenant-Id", "local");
        String userId = headerOrDefault(request, "X-User-Id", "local-user");
        return new ActorContext(tenantId, userId, Set.of("LOCAL_USER"), Set.of("agent:run"));
    }

    private static String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
