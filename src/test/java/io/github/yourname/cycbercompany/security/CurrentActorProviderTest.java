package io.github.yourname.cycbercompany.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CurrentActorProviderTest {

    @Test
    void usesRequestIpAsTemporaryUserIdentity() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties("local", "ignored"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "attacker-tenant");
        request.addHeader("X-User-Id", "attacker-user");

        ActorContext actor = provider.current(request);

        assertThat(actor.tenantId()).isEqualTo("local");
        assertThat(actor.userId()).isEqualTo("ip:127.0.0.1");
    }

    @Test
    void separatesDifferentClientIps() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties("local", "ignored"));
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("192.168.1.10");
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("192.168.1.11");

        assertThat(provider.current(first).userId()).isNotEqualTo(provider.current(second).userId());
    }

    @Test
    void usesFirstForwardedAddress() {
        CurrentActorProvider provider = new CurrentActorProvider(new SecurityProperties("local", "ignored"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 127.0.0.1");

        assertThat(provider.current(request).userId()).isEqualTo("ip:203.0.113.10");
    }
}
