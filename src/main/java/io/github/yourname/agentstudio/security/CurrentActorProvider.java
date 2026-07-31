package io.github.yourname.agentstudio.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 将 HTTP 请求转换为当前调用者上下文的本地开发适配器。
 *
 * <p>{@code X-Tenant-Id} 和 {@code X-User-Id} 仅用于演示、测试及观察租户隔离，
 * 并不是真正的身份认证。生产环境应在这个类的同一位置从 JWT 或 SSO Principal 构造上下文。
 */
@Component
public class CurrentActorProvider {

    public ActorContext current(HttpServletRequest request) {
        // 不信任调用方未提供的字段时使用固定本地身份，确保每次服务调用都有完整租户范围。
        String tenantId = headerOrDefault(request, "X-Tenant-Id", "local");
        String userId = headerOrDefault(request, "X-User-Id", "local-user");
        return new ActorContext(tenantId, userId, Set.of("LOCAL_USER"), Set.of("agent:run"));
    }

    private static String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
