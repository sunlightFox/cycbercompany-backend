package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeToolRequestPolicyTest {

    @Test
    void rejectsDangerousSchemesPrivateAddressesAndEmbeddedCredentials() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        for (String url : List.of(
                "file:///C:/Windows/win.ini",
                "data:text/plain,secret",
                "http://localhost:8080",
                "http://127.0.0.1:8080",
                "http://10.0.0.8",
                "http://169.254.169.254/latest/meta-data",
                "https://user:password@8.8.8.8")) {
            assertThatThrownBy(() -> policy.prepare("browser.open", Map.of("url", url)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void allowsPublicHttpAndInjectsOnlyServerOwnedPolicy() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        Map<String, Object> prepared = policy.prepare("browser.open", Map.of(
                "url", "https://8.8.8.8/",
                NodeToolRequestPolicy.BROWSER_POLICY_ARGUMENT,
                Map.of(NodeToolRequestPolicy.ALLOWED_PRIVATE_HOSTS, List.of("127.0.0.1"))));

        assertThat(prepared.get("url")).isEqualTo("https://8.8.8.8/");
        assertThat(prepared.get(NodeToolRequestPolicy.BROWSER_POLICY_ARGUMENT))
                .isEqualTo(Map.of(NodeToolRequestPolicy.ALLOWED_PRIVATE_HOSTS, List.of()));
    }

    @Test
    void permitsOnlyAnExplicitlyConfiguredPrivateHost() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(
                new BrowserPolicyProperties(List.of("127.0.0.1"), 4_096));

        Map<String, Object> prepared = policy.prepare(
                "browser.open", Map.of("url", "http://127.0.0.1:8080/app"));

        assertThat(prepared.get(NodeToolRequestPolicy.BROWSER_POLICY_ARGUMENT))
                .isEqualTo(Map.of(NodeToolRequestPolicy.ALLOWED_PRIVATE_HOSTS, List.of("127.0.0.1")));
        assertThatThrownBy(() -> policy.prepare("browser.open", Map.of("url", "http://192.168.1.20")))
                .hasMessageContaining("private host");
    }

    @Test
    void removesCallerControlledScreenshotPath() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        Map<String, Object> prepared = policy.prepare(
                "browser.screenshot", Map.of("path", "C:\\Windows\\outside.png", "fullPage", false));

        assertThat(prepared).containsEntry("fullPage", false).doesNotContainKey("path");
    }

    @Test
    void rejectsTruncatedPowerShellCommandBeforeApproval() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "powershell -NoProfile -Command ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PowerShell -Command is incomplete");
    }

    @Test
    void acceptsQuoteSafePowerShellWrapperAndCompleteScript() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        Map<String, Object> wrapped = policy.prepare("system.shell.run", Map.of(
                "command", "cmd /c powershell -NoProfile -Command \"Write-Output 'quoted-success'\""));

        assertThat(wrapped.get("command"))
                .isEqualTo("cmd /c powershell -NoProfile -Command \"Write-Output 'quoted-success'\"");
    }
}
