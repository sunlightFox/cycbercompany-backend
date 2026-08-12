package io.github.yourname.agentstudio.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.Locale;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 将 HTTP 请求转换为当前调用者上下文的本地开发适配器。
 *
 * <p>本地模式始终返回固定身份并忽略调用方请求头；远程模式只接受认证过滤器写入的
 * {@link StudioPrincipal}。因此租户和用户范围不能再通过 HTTP Header 伪造。
 */
@Component
public class CurrentActorProvider {

    private final SecurityProperties properties;

    public CurrentActorProvider(SecurityProperties properties) {
        this.properties = properties;
    }

    public ActorContext current(HttpServletRequest request) {
        if (!properties.tokenMode()) {
            // LOCAL mode is intentionally temporary demo identity. Keep the tenant shared so
            // seeded models/agents remain available, while conversation ownership is per client IP.
            String address = request == null ? null : firstForwardedAddress(request.getHeader("X-Forwarded-For"));
            if (address == null || address.isBlank()) {
                address = request == null ? null : request.getRemoteAddr();
            }
            String ip = address == null || address.isBlank() ? "unknown" : address.trim().toLowerCase(Locale.ROOT);
            return new ActorContext("local", "ip:" + ip, Set.of("LOCAL_USER"), Set.of("agent:run"));
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof StudioPrincipal principal) {
            return new ActorContext(
                    principal.tenantId(),
                    principal.userId(),
                    Set.of("REMOTE_USER", "NODE_TOOL_APPROVER"),
                    Set.of("agent:run"));
        }
        throw new AuthenticationCredentialsNotFoundException("No authenticated Agent Studio principal.");
    }

    private static String firstForwardedAddress(String header) {
        if (header == null || header.isBlank()) return null;
        String first = header.split(",", 2)[0].trim();
        return first.isBlank() ? null : first;
    }
}
