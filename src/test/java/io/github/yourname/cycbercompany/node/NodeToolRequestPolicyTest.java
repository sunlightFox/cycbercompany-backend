package io.github.yourname.cycbercompany.node;

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

    @Test
    void rejectsLongRunningShellCommandsBeforeApproval() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "npm run dev")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("short-lived commands")
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("shell.run", Map.of(
                "command", "npx vite preview")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("process.start");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "npm.cmd run dev")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "npm run preview")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "npm run watch")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "npx.cmd vite preview")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "python -m flask run")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "docker compose up")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "docker compose -f deploy/docker-compose.yml up --build")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start");
        assertThatThrownBy(() -> policy.prepare("shell.run", Map.of(
                "command", "Start-Process npm -ArgumentList 'run dev'")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("process.start");
        assertThatThrownBy(() -> policy.prepare("shell.run", Map.of(
                "command", "python app.py &")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("process.start");
    }

    @Test
    void acceptsShortLivedShellCommandsBeforeApproval() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        Map<String, Object> prepared = policy.prepare("system.shell.run", Map.of(
                "command", "npm install"));

        assertThat(prepared).containsEntry("command", "npm install");
        Map<String, Object> compose = policy.prepare("system.shell.run", Map.of(
                "command", "docker compose up -d --build"));

        assertThat(compose).containsEntry("command", "docker compose up -d --build");
        Map<String, Object> composeWithFile = policy.prepare("system.shell.run", Map.of(
                "command", "docker compose -f deploy/docker-compose.yml up -d --build"));

        assertThat(composeWithFile).containsEntry(
                "command", "docker compose -f deploy/docker-compose.yml up -d --build");
    }

    @Test
    void removesExplicitNullArgumentsBeforeCopyingThePreparedRequest() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("command", "npm install");
        arguments.put("cwd", null);
        arguments.put("timeoutSeconds", null);

        Map<String, Object> prepared = policy.prepare("system.shell.run", arguments);

        assertThat(prepared)
                .containsEntry("command", "npm install")
                .doesNotContainKeys("cwd", "timeoutSeconds");
    }

    @Test
    void removesNullOptionalFilesystemArgumentsBeforeApproval() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("path", "C:\\Users\\alice\\Desktop\\old.txt");
        arguments.put("recursive", null);

        Map<String, Object> prepared = policy.prepare("system.fs.delete", arguments);

        assertThat(prepared)
                .containsEntry("path", "C:\\Users\\alice\\Desktop\\old.txt")
                .doesNotContainKey("recursive");
    }

    @Test
    void rejectsUnreplacedPathPlaceholdersBeforeApproval() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        assertThatThrownBy(() -> policy.prepare("system.fs.mkdir", Map.of("path", "<path>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreplaced placeholder")
                .hasMessageContaining("path");
        assertThatThrownBy(() -> policy.prepare("system.fs.mkdir", Map.of("path", "<workspace>\\materials")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreplaced placeholder")
                .hasMessageContaining("path");
        assertThatThrownBy(() -> policy.prepare("system.process.start", Map.of(
                "command", "npm run dev",
                "stdoutPath", "<absolute path>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stdoutPath")
                .hasMessageContaining("unreplaced placeholder");
        assertThatThrownBy(() -> policy.prepare("fs.apply_patch_batch", Map.of(
                "changes", List.of(Map.of("path", "<file>", "expected", "old", "replacement", "new")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changes[].path");
        assertThatThrownBy(() -> policy.prepare("system.shell.run", Map.of(
                "command", "dir",
                "cwd", "<workspace>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cwd")
                .hasMessageContaining("unreplaced placeholder");
        assertThatThrownBy(() -> policy.prepare("system.desktop.organize.mkdir", Map.of(
                "path", "<desktop path>\\materials")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path")
                .hasMessageContaining("unreplaced placeholder");
    }

    @Test
    void allowsPlaceholderTextInNonPathContentArguments() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        Map<String, Object> prepared = policy.prepare("system.fs.write", Map.of(
                "path", "C:\\Users\\alice\\Desktop\\note.txt",
                "content", "Literal documentation: replace <path> with a real directory."));

        assertThat(prepared)
                .containsEntry("path", "C:\\Users\\alice\\Desktop\\note.txt")
                .containsEntry("content", "Literal documentation: replace <path> with a real directory.");
    }

    @Test
    void rejectsDetachedManagedProcessCommandsBeforeApproval() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        assertThatThrownBy(() -> policy.prepare("system.process.start", Map.of(
                "command", "powershell Start-Process npm -ArgumentList run,dev")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start manages the process itself");
        assertThatThrownBy(() -> policy.prepare("system.process.start", Map.of(
                "command", "cmd /c start npm run dev")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system.process.start manages the process itself");
        assertThatThrownBy(() -> policy.prepare("process.start", Map.of(
                "command", "nohup npm run dev &")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Use a foreground command");
        assertThatThrownBy(() -> policy.prepare("process.start", Map.of(
                "command", "Start-Job { npm run dev }")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Use a foreground command");
        assertThatThrownBy(() -> policy.prepare("process.start", Map.of(
                "command", "setsid npm run dev")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Use a foreground command");
    }

    @Test
    void acceptsForegroundManagedProcessCommands() {
        NodeToolRequestPolicy policy = new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults());

        Map<String, Object> prepared = policy.prepare("system.process.start", Map.of(
                "command", "npm run dev",
                "cwd", "C:\\Users\\alice\\Desktop\\materials-system"));

        assertThat(prepared)
                .containsEntry("command", "npm run dev")
                .containsEntry("cwd", "C:\\Users\\alice\\Desktop\\materials-system");
    }
}
