package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Starts development processes under an explicit node-owned handle.
 *
 * <p>Unlike a detached shell command, the returned handle remains associated with the root process
 * and its descendants, so a later {@code process.stop} can reliably clean up a local server.
 */
public final class ManagedProcessTool implements AutoCloseable {

    private static final int MAX_COMMAND_CHARS = 8_000;
    private static final int MAX_PROCESSES = 32;

    private final Path workspaceRoot;
    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    public ManagedProcessTool(Path workspaceRoot) {
        try {
            if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
                throw new IllegalArgumentException("Workspace must be an existing directory: " + workspaceRoot);
            }
            this.workspaceRoot = workspaceRoot.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve workspace: " + workspaceRoot, ex);
        }
    }

    public ToolExecutionResult start(Map<String, Object> arguments) {
        String command = value(arguments, "command");
        if (command == null || command.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: command");
        }
        if (command.length() > MAX_COMMAND_CHARS) {
            return ToolExecutionResult.failure("Command exceeds the " + MAX_COMMAND_CHARS + " character limit.");
        }
        if (startsDetachedProcess(command)) {
            return ToolExecutionResult.failure(
                    "process.start manages the command itself; do not use Start-Process, nohup, or a trailing '&'.");
        }
        long activeProcesses = processes.values().stream().filter(process -> process.process().isAlive()).count();
        if (activeProcesses >= MAX_PROCESSES) {
            return ToolExecutionResult.failure("Too many managed processes. Stop an existing process first.");
        }

        try {
            Path cwd = resolveDirectory(value(arguments, "cwd"));
            String processId = "proc_" + UUID.randomUUID();
            Path logs = workspaceRoot.resolve(".agent-studio").resolve("processes");
            Files.createDirectories(logs);
            Path stdout = resolveOutputPath(value(arguments, "stdoutPath"), logs.resolve(processId + ".out.log"));
            Path stderr = resolveOutputPath(value(arguments, "stderrPath"), logs.resolve(processId + ".err.log"));
            Files.createDirectories(stdout.getParent());
            Files.createDirectories(stderr.getParent());

            Process process = new ProcessBuilder(shellCommand(command))
                    .directory(cwd.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            ManagedProcess managed = new ManagedProcess(processId, command, cwd, stdout, stderr, process, Instant.now());
            processes.put(processId, managed);
            return ToolExecutionResult.success(snapshot(managed));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("process.start failed: " + message(ex));
        }
    }

    public ToolExecutionResult status(Map<String, Object> arguments) {
        ManagedProcess process = require(arguments);
        return process == null
                ? ToolExecutionResult.failure("Unknown managed process. Pass the processId returned by process.start.")
                : ToolExecutionResult.success(snapshot(process));
    }

    public ToolExecutionResult stop(Map<String, Object> arguments) {
        ManagedProcess managed = require(arguments);
        if (managed == null) {
            return ToolExecutionResult.failure("Unknown managed process. Pass the processId returned by process.start.");
        }
        try {
            terminateTree(managed.process(), false);
            if (managed.process().isAlive()) {
                managed.process().waitFor(2, TimeUnit.SECONDS);
            }
            if (managed.process().isAlive()) {
                terminateTree(managed.process(), true);
                managed.process().waitFor(2, TimeUnit.SECONDS);
            }
            Map<String, Object> result = snapshot(managed);
            result.put("stopped", !managed.process().isAlive());
            return managed.process().isAlive()
                    ? ToolExecutionResult.failure(result, "Managed process did not stop within the allowed time.")
                    : ToolExecutionResult.success(result);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure("process.stop was interrupted.");
        } catch (Exception ex) {
            return ToolExecutionResult.failure("process.stop failed: " + message(ex));
        }
    }

    @Override
    public void close() {
        for (ManagedProcess process : processes.values()) {
            try {
                terminateTree(process.process(), true);
            } catch (Exception ignored) {
                // Shutdown should continue trying remaining managed processes.
            }
        }
        processes.clear();
    }

    private ManagedProcess require(Map<String, Object> arguments) {
        String processId = value(arguments, "processId");
        return processId == null || processId.isBlank() ? null : processes.get(processId);
    }

    private Map<String, Object> snapshot(ManagedProcess managed) {
        Process process = managed.process();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processId", managed.id());
        result.put("rootPid", process.pid());
        result.put("active", process.isAlive());
        result.put("childPids", process.toHandle().descendants().map(ProcessHandle::pid).toList());
        result.put("command", managed.command());
        result.put("cwd", managed.cwd().toString());
        result.put("stdoutPath", managed.stdout().toString());
        result.put("stderrPath", managed.stderr().toString());
        result.put("startedAt", managed.startedAt().toString());
        if (!process.isAlive()) {
            result.put("exitCode", process.exitValue());
        }
        return result;
    }

    private Path resolveDirectory(String requested) throws IOException {
        Path candidate = requested == null || requested.isBlank() ? workspaceRoot : Path.of(requested);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("Working directory does not exist: " + candidate);
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Working directory must stay inside the configured workspace.");
        }
        return real;
    }

    private Path resolveOutputPath(String requested, Path fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        Path candidate = Path.of(requested);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        candidate = candidate.normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Log path must stay inside the configured workspace.");
        }
        return candidate;
    }

    private static boolean startsDetachedProcess(String command) {
        String normalized = command.toLowerCase(Locale.ROOT).trim();
        return normalized.contains("start-process")
                || normalized.contains("nohup ")
                || normalized.endsWith(" &");
    }

    private static List<String> shellCommand(String command) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-lc", command);
    }

    private static void terminateTree(Process process, boolean forcibly) {
        List<ProcessHandle> descendants = new ArrayList<>(process.toHandle().descendants().toList());
        for (int index = descendants.size() - 1; index >= 0; index--) {
            if (forcibly) {
                descendants.get(index).destroyForcibly();
            } else {
                descendants.get(index).destroy();
            }
        }
        if (forcibly) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }

    private static String value(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? null : value.toString();
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record ManagedProcess(
            String id,
            String command,
            Path cwd,
            Path stdout,
            Path stderr,
            Process process,
            Instant startedAt) {
    }
}
