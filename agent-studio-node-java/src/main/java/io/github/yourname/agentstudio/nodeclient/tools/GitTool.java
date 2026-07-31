package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * 把调用者明确列出的文件放入 Git 暂存区。
     *
     * <p>暂存会改变仓库状态，因此注册表将此工具标记为“高风险 + 必须审批”。
     * 这里再次校验每个路径，防止审批后的参数意外指向工作区外。
     */
    public ToolExecutionResult stage(Map<String, Object> arguments) {
        try {
            List<String> paths = workspacePaths(arguments);
            ToolExecutionResult staged = run(join(List.of("git", "add", "--"), paths));
            if (!staged.success()) {
                return staged;
            }
            return ToolExecutionResult.success(Map.of("stagedPaths", paths, "output", staged.result().get("output")));
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }
    }

    /**
     * 基于当前暂存区创建一个提交，不隐式执行 git add，也不跳过项目自身的 Git hooks。
     */
    public ToolExecutionResult commit(Map<String, Object> arguments) {
        String message = arguments == null || arguments.get("message") == null ? "" : arguments.get("message").toString().trim();
        if (message.isBlank() || message.length() > 200 || message.contains("\n") || message.contains("\r")) {
            return ToolExecutionResult.failure("git.commit message must be one non-empty line of at most 200 characters.");
        }
        if (!hasStagedChanges()) {
            return ToolExecutionResult.failure("git.commit requires staged changes. Review git.diff, then use approved git.stage first.");
        }
        return run(List.of("git", "commit", "-m", message));
    }

    private List<String> workspacePaths(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("paths");
        if (!(value instanceof List<?> rawPaths) || rawPaths.isEmpty() || rawPaths.size() > 100) {
            throw new IllegalArgumentException("git.stage requires 1 to 100 workspace-relative paths.");
        }
        List<String> paths = new ArrayList<>();
        for (Object rawPath : rawPaths) {
            if (!(rawPath instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("git.stage paths must be non-empty strings.");
            }
            Path path = Path.of(text.trim());
            if (path.isAbsolute()) {
                throw new IllegalArgumentException("git.stage paths must be workspace-relative.");
            }
            Path resolved = workspaceRoot.resolve(path).normalize();
            if (!resolved.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("git.stage paths must stay inside the configured workspace.");
            }
            String relative = workspaceRoot.relativize(resolved).toString().replace('\\', '/');
            // "." 或 "src/.." 会在 Git 中等价于整个工作区，不能作为“明确文件列表”。
            if (relative.isBlank() || ".".equals(relative)) {
                throw new IllegalArgumentException("git.stage must name files or subdirectories, not the whole workspace.");
            }
            paths.add(relative);
        }
        return paths.stream().distinct().toList();
    }

    private boolean hasStagedChanges() {
        try {
            Process process = new ProcessBuilder("git", "diff", "--cached", "--quiet")
                    .directory(workspaceRoot.toFile())
                    .start();
            // Git 使用退出码 1 表示“存在差异”，这是正常状态而不是命令错误。
            return process.waitFor() == 1;
        } catch (Exception ex) {
            return false;
        }
    }

    private static List<String> join(List<String> prefix, List<String> values) {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(values);
        return command;
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
