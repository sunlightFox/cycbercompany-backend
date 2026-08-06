package io.github.yourname.agentstudio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 使用常量时间比较验证远程个人版 API Bearer Token。 */
@Component
public final class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;

    public ApiTokenAuthenticationFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.tokenMode()) {
            return true;
        }
        String path = request.getRequestURI();
        return "/actuator/health".equals(path)
                || "/api/v1/system/status".equals(path)
                || path.startsWith("/swagger-ui/")
                || "/swagger-ui.html".equals(path)
                || path.startsWith("/v3/api-docs")
                || "/api/v1/nodes/register".equals(path)
                || "/api/v1/node-channel".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String supplied = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!constantTimeEquals(properties.apiToken(), supplied)) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Bearer token is missing or invalid.");
            return;
        }

        StudioPrincipal principal = new StudioPrincipal(properties.tenantId(), properties.userId());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "";
        }
        return authorization.substring(7).trim();
    }

    static boolean constantTimeEquals(String expected, String supplied) {
        byte[] left = (expected == null ? "" : expected).getBytes(StandardCharsets.UTF_8);
        byte[] right = (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right) && left.length > 0;
    }
}
