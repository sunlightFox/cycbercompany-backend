package io.github.yourname.agentstudio.nodeclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeClientWindowTest {

    @Test
    void managesOnlyItsOwnCurrentUserStartupEntry() throws Exception {
        Path startupFolder = Files.createTempDirectory("agent-studio-startup");
        Path executable = Files.createTempFile("agent-studio-node", ".exe");
        WindowsLoginStartup startup = new WindowsLoginStartup(startupFolder, executable);

        assertThat(startup.isEnabled()).isFalse();
        startup.setEnabled(true);
        assertThat(startup.isEnabled()).isTrue();
        assertThat(Files.readString(startup.startupFile(), StandardCharsets.UTF_16LE))
                .contains("Agent Studio managed login startup")
                .contains(executable.toAbsolutePath().normalize().toString())
                .contains("--background");

        startup.setEnabled(false);
        assertThat(Files.exists(startup.startupFile())).isFalse();
    }

    @Test
    void refusesToOverwriteOrDeleteAnUnmanagedStartupEntry() throws Exception {
        Path startupFolder = Files.createTempDirectory("agent-studio-startup-unmanaged");
        WindowsLoginStartup startup = new WindowsLoginStartup(
                startupFolder, Files.createTempFile("agent-studio-node", ".exe"));
        String foreignEntry = "@echo off\r\nstart another-app.exe\r\n";
        Files.writeString(startup.startupFile(), foreignEntry, StandardCharsets.UTF_16LE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> startup.setEnabled(true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not managed by Agent Studio");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> startup.setEnabled(false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not managed by Agent Studio");
        assertThat(Files.readString(startup.startupFile(), StandardCharsets.UTF_16LE)).isEqualTo(foreignEntry);
    }

    @Test
    void optionFirstPackagedInvocationUsesGuiMode() {
        assertThat(AgentStudioNodeApplication.commandFor(new String[]{"--config", "D:/node.json"}))
                .isEqualTo("gui");
        assertThat(AgentStudioNodeApplication.commandFor(new String[]{"gui", "--config", "D:/node.json"}))
                .isEqualTo("gui");
        assertThat(AgentStudioNodeApplication.commandFor(new String[]{"start", "--config", "D:/node.json"}))
                .isEqualTo("start");
    }

    @Test
    void parsesOptionsPrependedByPackagedLauncher() {
        assertThat(CliArgs.parse(new String[]{
                "--server", "http://127.0.0.1:18088",
                "--workspace", "D:/work/project",
                "--config", "D:/node.json",
                "gui"}))
                .containsEntry("server", "http://127.0.0.1:18088")
                .containsEntry("workspace", "D:/work/project")
                .containsEntry("config", "D:/node.json");
    }

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
        assertThat(NodeClientWindow.shouldAutoStart(Map.of(), true)).isTrue();
        assertThat(NodeClientWindow.shouldAutoStart(Map.of("no-auto-start", "true"), true)).isFalse();
        assertThat(NodeClientWindow.runsInBackground(Map.of("background", "true"))).isTrue();
        assertThat(NodeClientWindow.runsInBackground(Map.of())).isFalse();
        assertThat(NodeClientWindow.shouldReprovisionOnStart(Map.of("background", "true"), true)).isFalse();
        assertThat(NodeClientWindow.shouldReprovisionOnStart(Map.of("background", "true"), false)).isTrue();
        assertThat(NodeClientWindow.shouldReprovisionOnStart(Map.of(), true)).isTrue();
        assertThat(NodeClientWindow.canConfigureLoginStartup(false)).isFalse();
        assertThat(NodeClientWindow.canConfigureLoginStartup(true)).isTrue();
    }

    @Test
    void retriesOnlyTransientBootstrapFailures() {
        assertThat(NodeClientWindow.isTransientStartupFailure(new ConnectException("connection refused"))).isTrue();
        assertThat(NodeClientWindow.isTransientStartupFailure(new IOException("connect timed out"))).isTrue();
        assertThat(NodeClientWindow.isTransientStartupFailure(
                new IllegalStateException("Local executor provisioning failed: HTTP 503 unavailable"))).isTrue();
        assertThat(NodeClientWindow.isTransientStartupFailure(
                new IllegalStateException("Local executor provisioning failed: HTTP 401 unauthorized"))).isFalse();
        assertThat(NodeClientWindow.isTransientStartupFailure(
                new IllegalStateException("Local executor provisioning failed: HTTP 500 This installation is configured for registered nodes only."))).isFalse();
        assertThat(NodeClientWindow.isTransientStartupFailure(
                new IllegalArgumentException("workspace must exist"))).isFalse();
        assertThat(NodeClientWindow.isTransientStartupFailure(
                new AccessDeniedException("C:/Users/example/.agent-studio-node/local-executor.json"))).isFalse();
    }

    @Test
    void usesBoundedExponentialBootstrapDelays() {
        assertThat(NodeClientWindow.retryDelayMillis(1)).isEqualTo(500);
        assertThat(NodeClientWindow.retryDelayMillis(4)).isEqualTo(4_000);
        assertThat(NodeClientWindow.retryDelayMillis(8)).isEqualTo(5_000);
        assertThat(NodeClientWindow.retryDelayMillis(100)).isEqualTo(5_000);
    }

    @Test
    void translatesStartupErrorsIntoActions() {
        assertThat(NodeClientWindow.startupFailureDetail(
                new IllegalStateException("Another Agent Studio node process is already running for this config")))
                .contains("\u5df2\u5728\u8fd0\u884c");
        assertThat(NodeClientWindow.startupFailureDetail(
                new IllegalStateException("Local executor provisioning failed: HTTP 401 unauthorized")))
                .contains("AGENT_STUDIO_API_TOKEN");
        assertThat(NodeClientWindow.startupFailureDetail(
                        new IllegalStateException("This installation is configured for registered nodes only.")))
                .contains("联系管理员");
        assertThat(NodeClientWindow.startupFailureDetail(
                        new IllegalArgumentException("Workspace must be an existing directory: D:/missing")))
                .contains("重新选择");
        assertThat(NodeClientWindow.startupFailureDetail(
                        new AccessDeniedException("Access is denied")))
                .contains("配置目录");
    }

    @Test
    void retriesTransientBootstrapUntilItSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> scheduledRetries = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        BootstrapRetryPolicy.execute(
                () -> {
                    if (attempts.incrementAndGet() < 3) throw new ConnectException("connection refused");
                },
                () -> false,
                scheduledRetries::add,
                delays::add);

        assertThat(attempts).hasValue(3);
        assertThat(scheduledRetries).containsExactly(2, 3);
        assertThat(delays).containsExactly(500, 1_000);
    }

    @Test
    void doesNotRetryDeterministicBootstrapFailure() {
        AtomicInteger attempts = new AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BootstrapRetryPolicy.execute(
                        () -> {
                            attempts.incrementAndGet();
                            throw new AccessDeniedException("C:/node-config.json");
                        },
                        () -> false,
                        ignored -> { },
                        ignored -> { }))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(attempts).hasValue(1);
    }
}
