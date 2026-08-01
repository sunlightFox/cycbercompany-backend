package io.github.yourname.agentstudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiTokenAuthenticationFilterTest {

    @Test
    void extractsOnlyBearerCredentialsAndUsesNonEmptyConstantTimeComparison() {
        assertThat(ApiTokenAuthenticationFilter.bearerToken("Bearer secret-value")).isEqualTo("secret-value");
        assertThat(ApiTokenAuthenticationFilter.bearerToken("Basic secret-value")).isEmpty();
        assertThat(ApiTokenAuthenticationFilter.constantTimeEquals("same", "same")).isTrue();
        assertThat(ApiTokenAuthenticationFilter.constantTimeEquals("same", "different")).isFalse();
        assertThat(ApiTokenAuthenticationFilter.constantTimeEquals("", "")).isFalse();
    }
}
