package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证高风险 Git 写入操作需要显式文件列表和已有暂存内容。 */
class GitToolTest {

    @Test
    void reportsNonRepositoryAsStructuredReadOnlyStatus() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-git-none");
        GitTool tool = new GitTool(workspace);

        var result = tool.status();

        assertTrue(result.success());
        assertEquals(false, result.result().get("repository"));
    }

    @Test
    void reviewsStagedUnstagedAndUntrackedFilesWithoutChangingGitState() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-git-review");
        run(workspace, "git", "init");
        run(workspace, "git", "config", "user.email", "test@example.invalid");
        run(workspace, "git", "config", "user.name", "CycberCompany Test");

        // 先建立两个已跟踪文件，随后分别制造暂存和未暂存变更。
        Files.writeString(workspace.resolve("staged.txt"), "before\n");
        Files.writeString(workspace.resolve("unstaged.txt"), "before\n");
        GitTool tool = new GitTool(workspace);
        assertTrue(tool.stage(Map.of("paths", List.of("staged.txt", "unstaged.txt"))).success());
        assertTrue(tool.commit(Map.of("message", "test: create review fixtures")).success());

        Files.writeString(workspace.resolve("staged.txt"), "after\n");
        assertTrue(tool.stage(Map.of("paths", List.of("staged.txt"))).success());
        Files.writeString(workspace.resolve("unstaged.txt"), "after\n");
        Files.writeString(workspace.resolve("untracked.txt"), "new\n");

        var review = tool.review();

        assertTrue(review.success());
        assertEquals(1, review.result().get("stagedFiles"));
        assertEquals(1, review.result().get("unstagedFiles"));
        assertEquals(1, review.result().get("untrackedFiles"));
        assertEquals(false, review.result().get("truncated"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) review.result().get("changes");
        assertTrue(changes.stream().anyMatch(change -> "staged.txt".equals(change.get("path"))));
        assertTrue(changes.stream().anyMatch(change -> "unstaged.txt".equals(change.get("path"))));
        assertTrue(changes.stream().anyMatch(change -> Boolean.TRUE.equals(change.get("untracked"))
                && "untracked.txt".equals(change.get("path"))));
    }

    @Test
    void readsAnExplicitStagedDiffWithoutMixingItWithUnstagedChanges() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-git-staged-diff");
        run(workspace, "git", "init");
        run(workspace, "git", "config", "user.email", "test@example.invalid");
        run(workspace, "git", "config", "user.name", "CycberCompany Test");
        Files.writeString(workspace.resolve("App.java"), "class App { String value = \"before\"; }\n");
        GitTool tool = new GitTool(workspace);
        assertTrue(tool.stage(Map.of("paths", List.of("App.java"))).success());
        assertTrue(tool.commit(Map.of("message", "test: create diff fixture")).success());

        Files.writeString(workspace.resolve("App.java"), "class App { String value = \"staged\"; }\n");
        assertTrue(tool.stage(Map.of("paths", List.of("App.java"))).success());
        Files.writeString(workspace.resolve("App.java"), "class App { String value = \"unstaged\"; }\n");

        var staged = tool.diff(Map.of("path", "App.java", "staged", true));
        var unstaged = tool.diff(Map.of("path", "App.java"));

        assertTrue(staged.success());
        assertTrue(staged.result().get("output").toString().contains("staged"));
        assertTrue(!staged.result().get("output").toString().contains("unstaged"));
        assertTrue(unstaged.success());
        assertTrue(unstaged.result().get("output").toString().contains("unstaged"));
    }

    @Test
    void stagesExplicitFilesThenCreatesACommit() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-git");
        run(workspace, "git", "init");
        run(workspace, "git", "config", "user.email", "test@example.invalid");
        run(workspace, "git", "config", "user.name", "CycberCompany Test");
        Files.writeString(workspace.resolve("README.md"), "initial\n");
        GitTool tool = new GitTool(workspace);

        var staged = tool.stage(Map.of("paths", List.of("README.md")));
        var committed = tool.commit(Map.of("message", "test: add readme"));

        assertTrue(staged.success());
        assertTrue(committed.success());
        assertFalse(tool.commit(Map.of("message", "test: empty commit")).success());
        assertFalse(tool.stage(Map.of("paths", List.of("../outside.txt"))).success());
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(new String(process.getInputStream().readAllBytes()));
        }
    }
}
