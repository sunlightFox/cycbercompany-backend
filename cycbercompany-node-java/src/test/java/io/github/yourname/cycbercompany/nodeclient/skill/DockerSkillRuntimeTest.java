package io.github.yourname.cycbercompany.nodeclient.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSkillRuntimeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsARestrictedDockerCommandWithoutPullingOrUsingHostNetwork() {
        RecordingRunner runner = new RecordingRunner(false);
        DockerSkillRuntime runtime = new DockerSkillRuntime(
                true,
                Map.of("python", "local-python:test", "node", "missing-node:test"),
                runner);

        DockerSkillRuntime.RuntimeResult result = runtime.run(
                "python",
                temporaryDirectory.resolve("cache/content"),
                temporaryDirectory.resolve("work"),
                "scripts/check.py",
                List.of("--mode", "strict"),
                45);

        assertTrue(result.succeeded());
        assertTrue(runtime.supports("python"));
        assertFalse(runtime.supports("node"));
        List<String> command = runner.commands.stream()
                .filter(parts -> parts.size() > 1 && "run".equals(parts.get(1)))
                .findFirst()
                .orElseThrow();
        assertTrue(command.containsAll(List.of(
                "--network", "none", "--read-only", "--memory", "256m", "--cpus", "1.0",
                "--pids-limit", "64", "--cap-drop", "ALL", "--security-opt", "no-new-privileges")));
        assertTrue(command.stream().anyMatch(value -> value.endsWith(",dst=/skill,readonly")));
        assertTrue(command.stream().anyMatch(value -> value.endsWith(",dst=/workspace")));
        assertTrue(command.containsAll(List.of("local-python:test", "python", "/skill/scripts/check.py", "--mode", "strict")));
        assertFalse(command.contains("pull"));
    }

    @Test
    void forceRemovesTheNamedContainerAfterTimeout() {
        RecordingRunner runner = new RecordingRunner(true);
        DockerSkillRuntime runtime = new DockerSkillRuntime(
                true, Map.of("python", "local-python:test"), runner);

        DockerSkillRuntime.RuntimeResult result = runtime.run(
                "python",
                temporaryDirectory.resolve("cache/content"),
                temporaryDirectory.resolve("work"),
                "scripts/check.py",
                List.of(),
                1);

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        List<String> cleanup = runner.commands.getLast();
        assertEquals(List.of("docker", "rm", "-f"), cleanup.subList(0, 3));
        assertTrue(cleanup.get(3).startsWith("cycbercompany-skill-"));
    }

    private static final class RecordingRunner implements DockerSkillRuntime.CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final boolean timeoutRun;

        private RecordingRunner(boolean timeoutRun) {
            this.timeoutRun = timeoutRun;
        }

        @Override
        public DockerSkillRuntime.CommandResult run(List<String> command, Duration timeout, int outputLimit) {
            commands.add(List.copyOf(command));
            if (command.size() > 2 && "image".equals(command.get(1)) && "inspect".equals(command.get(2))) {
                return command.contains("local-python:test")
                        ? new DockerSkillRuntime.CommandResult(0, false, "present")
                        : new DockerSkillRuntime.CommandResult(1, false, "missing");
            }
            if (command.size() > 1 && "run".equals(command.get(1))) {
                return timeoutRun
                        ? new DockerSkillRuntime.CommandResult(-1, true, "timed out")
                        : new DockerSkillRuntime.CommandResult(0, false, "ok");
            }
            return new DockerSkillRuntime.CommandResult(0, false, "cleaned");
        }
    }
}
