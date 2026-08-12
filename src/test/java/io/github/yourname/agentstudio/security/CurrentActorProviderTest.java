package io.github.yourname.agentstudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentActorProviderTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void localModeUsesRequestIpAsTemporaryUserIdentity() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties(
                SecurityProperties.Mode.LOCAL, "", "ignored", "ignored"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "attacker-tenant");
        request.addHeader("X-User-Id", "attacker-user");

        ActorContext actor = provider.current(request);

        assertThat(actor.tenantId()).isEqualTo("local");
        assertThat(actor.userId()).isEqualTo("ip:127.0.0.1");
    }

    @Test
    void localModeSeparatesDifferentClientIps() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties(
                SecurityProperties.Mode.LOCAL, "", "ignored", "ignored"));
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("192.168.1.10");
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("192.168.1.11");

        assertThat(provider.current(first).userId()).isNotEqualTo(provider.current(second).userId());
    }

    @Test
    void localModeUsesFirstForwardedAddressFromTrustedProxy() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties(
                SecurityProperties.Mode.LOCAL, "", "ignored", "ignored"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 127.0.0.1");

        assertThat(provider.current(request).userId()).isEqualTo("ip:203.0.113.10");
    }

    @Test
    void tokenModeUsesOnlyTheAuthenticatedPrincipal() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties(
                SecurityProperties.Mode.TOKEN, "a".repeat(32), "configured", "configured"));
        StudioPrincipal principal = new StudioPrincipal("trusted-tenant", "trusted-user");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        ActorContext actor = provider.current(new MockHttpServletRequest());

        assertThat(actor.tenantId()).isEqualTo("trusted-tenant");
        assertThat(actor.userId()).isEqualTo("trusted-user");
    }

    @Test
    void tokenModeFailsClosedWithoutAuthentication() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties(
                SecurityProperties.Mode.TOKEN, "a".repeat(32), "tenant", "user"));

        assertThatThrownBy(() -> provider.current(new MockHttpServletRequest()))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }
}
