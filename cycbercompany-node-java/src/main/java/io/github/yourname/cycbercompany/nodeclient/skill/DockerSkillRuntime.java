package io.github.yourname.cycbercompany.nodeclient.skill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 只通过 Docker 执行第三方 Skill 脚本的受限运行时。
 *
 * <p>Docker 必须由管理员显式启用，所需镜像也必须已存在；运行时不会隐式 pull 或安装依赖。
 * 这样网络策略不会在容器启动前被镜像下载绕过。
 */
public final class DockerSkillRuntime {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private final CommandRunner runner;
    private final Map<String, String> availableImages;

    public static DockerSkillRuntime fromEnvironment() {
        boolean enabled = "docker".equalsIgnoreCase(System.getenv("CYCBERCOMPANY_SKILL_RUNTIME"));
        Map<String, String> configured = Map.of(
                "python", env("CYCBERCOMPANY_SKILL_PYTHON_IMAGE", "python:3.12-alpine"),
                "node", env("CYCBERCOMPANY_SKILL_NODE_IMAGE", "node:22-alpine"),
                "shell", env("CYCBERCOMPANY_SKILL_SHELL_IMAGE", "alpine:3.20"));
        return new DockerSkillRuntime(enabled, configured, new ProcessCommandRunner());
    }

    DockerSkillRuntime(boolean enabled, Map<String, String> configuredImages, CommandRunner runner) {
        this.runner = runner;
        Map<String, String> detected = new LinkedHashMap<>();
        if (enabled) {
            for (Map.Entry<String, String> entry : configuredImages.entrySet()) {
                CommandResult result = runner.run(
                        List.of("docker", "image", "inspect", entry.getValue()), Duration.ofSeconds(10), 4_096);
                if (result.exitCode() == 0 && !result.timedOut()) {
                    detected.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.availableImages = Map.copyOf(detected);
    }

    public boolean supports(String runtime) {
        return runtime != null && availableImages.containsKey(runtime.toLowerCase(Locale.ROOT));
    }

    /** 返回可验证的镜像标识，供节点 capability snapshot 和兼容预检使用。 */
    public Map<String, String> runtimes() {
        return availableImages;
    }

    public RuntimeResult run(
            String runtime,
            Path immutableBundle,
            Path writableWorkspace,
            String entrypoint,
            List<String> arguments,
            int timeoutSeconds) {
        String normalizedRuntime = runtime == null ? "" : runtime.toLowerCase(Locale.ROOT);
        String image = availableImages.get(normalizedRuntime);
        if (image == null) {
            return new RuntimeResult(false, -1, false, "Docker Skill runtime is not available for: " + runtime);
        }
        if (entrypoint == null || !entrypoint.matches("scripts/[A-Za-z0-9._/-]{1,240}")) {
            return new RuntimeResult(false, -1, false, "Invalid Skill script entrypoint.");
        }
        if (immutableBundle.toString().contains(",") || writableWorkspace.toString().contains(",")) {
            return new RuntimeResult(false, -1, false, "Docker bind mount paths must not contain commas.");
        }
        int boundedTimeout = Math.max(1, Math.min(timeoutSeconds, 120));
        String containerName = "cycbercompany-skill-" + UUID.randomUUID().toString().substring(0, 12);
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--name", containerName,
                "--network", "none",
                "--read-only",
                "--memory", "256m",
                "--cpus", "1.0",
                "--pids-limit", "64",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "--mount", "type=bind,src=" + immutableBundle.toAbsolutePath().normalize() + ",dst=/skill,readonly",
                "--mount", "type=bind,src=" + writableWorkspace.toAbsolutePath().normalize() + ",dst=/workspace",
                "--workdir", "/workspace",
                image));
        command.addAll(interpreter(normalizedRuntime, entrypoint));
        if (arguments != null) {
            if (arguments.size() > 32 || arguments.stream().anyMatch(value -> value == null || value.length() > 2_000)) {
                return new RuntimeResult(false, -1, false, "Skill script arguments exceed the policy limit.");
            }
            command.addAll(arguments);
        }
        CommandResult result = runner.run(command, Duration.ofSeconds(boundedTimeout), MAX_OUTPUT_BYTES);
        if (result.timedOut()) {
            // 停止 docker CLI 不一定等于停止容器，按节点生成的固定名称做一次强制清理。
            runner.run(List.of("docker", "rm", "-f", containerName), Duration.ofSeconds(10), 4_096);
        }
        return new RuntimeResult(result.exitCode() == 0 && !result.timedOut(), result.exitCode(), result.timedOut(), result.output());
    }

    private static List<String> interpreter(String runtime, String entrypoint) {
        String script = "/skill/" + entrypoint;
        return switch (runtime) {
            case "python" -> List.of("python", script);
            case "node" -> List.of("node", script);
            case "shell" -> List.of("/bin/sh", script);
            default -> throw new IllegalArgumentException("Unsupported Docker Skill runtime: " + runtime);
        };
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record RuntimeResult(boolean succeeded, int exitCode, boolean timedOut, String output) {
    }

    interface CommandRunner {
        CommandResult run(List<String> command, Duration timeout, int outputLimit);
    }

    record CommandResult(int exitCode, boolean timedOut, String output) {
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, Duration timeout, int outputLimit) {
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var output = executor.submit(() -> readBoundedAndDrain(process.getInputStream(), outputLimit));
                    boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    if (!finished) process.destroyForcibly();
                    String text = output.get(10, TimeUnit.SECONDS);
                    return new CommandResult(finished ? process.exitValue() : -1, !finished, text);
                }
            } catch (Exception ex) {
                return new CommandResult(-1, false, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
        }

        private static String readBoundedAndDrain(InputStream input, int limit) throws IOException {
            ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(limit, 8_192));
            byte[] buffer = new byte[8 * 1024];
            int read;
            boolean truncated = false;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                int remaining = Math.max(0, limit - kept.size());
                if (remaining > 0) kept.write(buffer, 0, Math.min(remaining, read));
                if (read > remaining) truncated = true;
            }
            String result = kept.toString(StandardCharsets.UTF_8);
            return truncated ? result + "\n... [output truncated by node policy]" : result;
        }
    }
}
