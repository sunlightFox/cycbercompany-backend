package io.github.yourname.cycbercompany.security;

/** 通过服务端认证后写入 Spring Security 上下文的可信身份。 */
public record StudioPrincipal(String tenantId, String userId) {
}
