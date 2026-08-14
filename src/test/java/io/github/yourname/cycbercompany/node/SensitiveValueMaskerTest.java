package io.github.yourname.cycbercompany.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证常见 JSON、命令行和 HTTP 授权形式的敏感值不会进入展示接口。 */
class SensitiveValueMaskerTest {

    @Test
    void masksJsonAssignmentsAndBearerCredentials() {
        String value = "{\"apiKey\":\"sk-secret-value\",\"safe\":\"visible\"} token=abc123 password: p4ss Bearer eyJhbGciOiJIUzI1NiJ9";

        String masked = SensitiveValueMasker.mask(value);

        assertThat(masked)
                .contains("\"apiKey\":\"***\"")
                .contains("token=***")
                .contains("password: ***")
                .contains("Bearer ***")
                .contains("\"safe\":\"visible\"")
                .doesNotContain("sk-secret-value", "abc123", "p4ss", "eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void masksSensitiveJvmSystemProperties() {
        String value = "JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStorePassword=do-not-display -Dapp.mode=test";

        String masked = SensitiveValueMasker.mask(value);

        assertThat(masked)
                .contains("-Djavax.net.ssl.trustStorePassword=***")
                .doesNotContain("do-not-display");
    }
}
