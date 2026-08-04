package io.github.yourname.agentstudio.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RemoteDeploymentSafetyValidatorTest {

    @Test
    void acceptsTheDefaultLoopbackLocalMode() {
        var validator = validator(SecurityProperties.Mode.LOCAL, "", "127.0.0.1", false, true);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void refusesRemoteBindingWithoutAuthenticationTlsAndConsoleHardening() {
        assertThatThrownBy(() -> validator(
                SecurityProperties.Mode.LOCAL, "", "0.0.0.0", false, true).afterPropertiesSet())
                .hasMessageContaining("mode must be TOKEN");

        assertThatThrownBy(() -> validator(
                SecurityProperties.Mode.TOKEN, "short", "0.0.0.0", true, false).afterPropertiesSet())
                .hasMessageContaining("at least 32");

        assertThatThrownBy(() -> validator(
                SecurityProperties.Mode.TOKEN, "x".repeat(32), "0.0.0.0", false, false).afterPropertiesSet())
                .hasMessageContaining("ssl.enabled");

        assertThatThrownBy(() -> validator(
                SecurityProperties.Mode.TOKEN, "x".repeat(32), "0.0.0.0", true, true).afterPropertiesSet())
                .hasMessageContaining("h2.console.enabled");
    }

    @Test
    void acceptsHardenedRemotePersonalDeployment() {
        var validator = validator(
                SecurityProperties.Mode.TOKEN, "x".repeat(32), "0.0.0.0", true, false);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnExplicitlyDeclaredLocalDockerProxyOnly() {
        SecurityProperties properties = new SecurityProperties(SecurityProperties.Mode.LOCAL, "", "tenant", "user");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("app.security.allow-local-proxy", "true")
                .withProperty("server.ssl.enabled", "false")
                .withProperty("spring.h2.console.enabled", "true");

        assertThatCode(new RemoteDeploymentSafetyValidator(properties, environment)::afterPropertiesSet)
                .doesNotThrowAnyException();
    }

    private static RemoteDeploymentSafetyValidator validator(
            SecurityProperties.Mode mode,
            String token,
            String address,
            boolean ssl,
            boolean h2Console) {
        SecurityProperties properties = new SecurityProperties(mode, token, "tenant", "user");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.address", address)
                .withProperty("server.ssl.enabled", Boolean.toString(ssl))
                .withProperty("spring.h2.console.enabled", Boolean.toString(h2Console));
        return new RemoteDeploymentSafetyValidator(properties, environment);
    }
}
