package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final int MAX_REVIEW_FILES = 300;
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
        Object rawStaged = arguments == null ? null : arguments.get("staged");
        if (rawStaged != null && !(rawStaged instanceof Boolean)) {
            return ToolExecutionResult.failure("git.diff staged must be a boolean when provided.");
        }
        boolean staged = Boolean.TRUE.equals(rawStaged);
        List<String> command = new ArrayList<>(List.of("git", "diff", "--no-ext-diff"));
        // git diff 默认不显示暂存区。交付审阅必须能覆盖 git.review 报告的两种已跟踪变更，
        // 因此由显式 staged=true 请求 --cached；绝不把两类 diff 隐式混在同一份输出中。
        if (staged) {
            command.add("--cached");
        }
        command.add("--");
        if (path != null && !path.isBlank()) {
            command.add(path);
        }
        return run(command);
    }

    /**
     * 汇总当前工作树中的已暂存、未暂存和未跟踪文件，用于编码任务交付前的最后审阅。
     *
     * <p>该方法只调用 porcelain 状态命令，不读取文件正文、不调用 diff，也不修改 Git 状态。
     * 路径和状态来自仓库数据，调用方必须把它们当作不可信内容而非指令。
     */
    public ToolExecutionResult review() {
        try {
            CommandOutput output = execute(List.of("git", "status", "--porcelain=v1", "--branch"));
            if (output.exitCode() != 0) {
                return ToolExecutionResult.failure("Git command failed: " + output.text().trim());
            }
            String branch = "unknown";
            int staged = 0;
            int unstaged = 0;
            int untracked = 0;
            boolean truncated = output.truncated();
            List<Map<String, Object>> changes = new ArrayList<>();
            for (String line : output.text().split("\\R")) {
                if (line.startsWith("## ")) {
                    branch = line.substring(3).trim();
                    continue;
                }
                if (line.length() < 3 || line.charAt(2) != ' ') {
                    continue;
                }
                String status = line.substring(0, 2);
                if (status.charAt(0) != ' ' && status.charAt(0) != '?') staged++;
                // Git 用 ?? 专门表示未跟踪文件。它不是已跟踪文件的“未暂存修改”，
                // 三个汇总数字必须互斥，调用方才能据此准确判断需要审阅的变更类别。
                if ("??".equals(status)) {
                    untracked++;
                } else if (status.charAt(1) != ' ') {
                    unstaged++;
                }
                if (changes.size() >= MAX_REVIEW_FILES) {
                    truncated = true;
                    continue;
                }
                changes.add(Map.of(
                        "path", line.substring(3),
                        "indexStatus", String.valueOf(status.charAt(0)),
                        "worktreeStatus", String.valueOf(status.charAt(1)),
                        "untracked", "??".equals(status)));
            }
            return ToolExecutionResult.success(Map.of(
                    "branch", branch,
                    "changes", changes,
                    "stagedFiles", staged,
                    "unstagedFiles", unstaged,
                    "untrackedFiles", untracked,
                    "truncated", truncated,
                    "guidance", "Review each listed path with git.diff or fs.read before delivery. This summary does not prove tests passed."));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("git.review failed: " + message(ex));
        }
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
            CommandOutput output = execute(command);
            if (output.exitCode() != 0) {
                return ToolExecutionResult.failure("Git command failed: " + output.text().trim());
            }
            return ToolExecutionResult.success(Map.of("output", output.text(), "truncated", output.truncated()));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Git command failed: " + message(ex));
        }
    }

    /** 继续排空 Git 的输出流，即使模型侧结果已达到上限，也不能让子进程因管道写满而挂起。 */
    private CommandOutput execute(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workspaceRoot.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean truncated = false;
        try (InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = stream.read(buffer)) != -1;) {
                int remaining = MAX_OUTPUT_BYTES - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(remaining, read));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
        }
        int exitCode = process.waitFor();
        String text = captured.toString(StandardCharsets.UTF_8);
        return new CommandOutput(exitCode, text, truncated);
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record CommandOutput(int exitCode, String text, boolean truncated) {
    }
}
