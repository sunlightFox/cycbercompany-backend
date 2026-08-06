package io.github.yourname.agentstudio.nodeclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeClientWindowTest {

    @Test
    void preservesPackagedServerAndWorkspaceOptions() {
        var options = NodeClientWindow.resolvedOptions(Map.of(
                "server", "http://127.0.0.1:8083",
                "workspace", "D:/work/project",
                "name", "Development computer"));

        assertThat(options).containsEntry("server", "http://127.0.0.1:8083")
                .containsEntry("workspace", "D:/work/project")
                .containsEntry("name", "Development computer")
                .containsEntry("access", "workspace");
    }

    @Test
    void suppliesLocalDefaultsWhenPackageOptionsAreMissing() {
        var options = NodeClientWindow.resolvedOptions(Map.of());

        assertThat(options.get("server")).isEqualTo("http://127.0.0.1:8080");
        assertThat(options.get("workspace")).isEqualTo(System.getProperty("user.home"));
        assertThat(options.get("name")).isEqualTo("Agent Studio Windows Node");
    }

    @Test
    void waitsForWorkspaceConfirmationOnFirstAutoStart() {
        assertThat(NodeClientWindow.shouldAutoStart(Map.of("auto-start", "true"), false)).isFalse();
        assertThat(NodeClientWindow.shouldAutoStart(Map.of("auto-start", "true"), true)).isTrue();
        assertThat(NodeClientWindow.shouldAutoStart(Map.of(), true)).isFalse();
    }
}
