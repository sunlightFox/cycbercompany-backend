package io.github.yourname.cycbercompany.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 服务端信任的当前操作者上下文。
 *
 * <p>本地模式可以从请求头创建它，生产模式可以从 JWT/OIDC 声明创建它。业务代码绝不相信
 * Prompt、工具参数或模型输出里携带的 tenant/user 信息。
 */
public record ActorContext(
        String tenantId,
        String userId,
        Set<String> roles,
        Set<String> scopes) {

    public ActorContext {
        roles = copyNonNull(roles);
        scopes = copyNonNull(scopes);
    }

    public static ActorContext local() {
        return new ActorContext("local", "local-user", Set.of("LOCAL_USER"), Set.of("agent:run"));
    }

    private static Set<String> copyNonNull(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> sanitized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null) {
                sanitized.add(value);
            }
        }
        return sanitized.isEmpty() ? Set.of() : Set.copyOf(sanitized);
    }
}
