package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Read-only Git inspection constrained to the configured workspace. */
public final class GitTool {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private final Path workspaceRoot;

    public GitTool(Path workspaceRoot) {
        try {
            this.workspaceRoot = workspaceRoot.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve workspace: " + workspaceRoot, ex);
        }
    }

    public ToolExecutionResult status() {
        return run(List.of("git", "status", "--short", "--branch"));
    }

    public ToolExecutionResult diff(Map<String, Object> arguments) {
        String path = arguments == null || arguments.get("path") == null ? null : arguments.get("path").toString();
        if (path != null && (path.contains("..") || Path.of(path).isAbsolute())) {
            return ToolExecutionResult.failure("git.diff path must be workspace-relative.");
        }
        return path == null || path.isBlank()
                ? run(List.of("git", "diff", "--no-ext-diff", "--"))
                : run(List.of("git", "diff", "--no-ext-diff", "--", path));
    }

    private ToolExecutionResult run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).directory(workspaceRoot.toFile()).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
            int exitCode = process.waitFor();
            boolean truncated = output.length > MAX_OUTPUT_BYTES;
            String text = new String(output, 0, truncated ? MAX_OUTPUT_BYTES : output.length, StandardCharsets.UTF_8);
            if (exitCode != 0) {
                return ToolExecutionResult.failure("Git command failed: " + text.trim());
            }
            return ToolExecutionResult.success(Map.of("output", text, "truncated", truncated));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Git command failed: " + ex.getMessage());
        }
    }
}
