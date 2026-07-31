package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证高风险 Git 写入操作需要显式文件列表和已有暂存内容。 */
class GitToolTest {

    @Test
    void stagesExplicitFilesThenCreatesACommit() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-git");
        run(workspace, "git", "init");
        run(workspace, "git", "config", "user.email", "test@example.invalid");
        run(workspace, "git", "config", "user.name", "Agent Studio Test");
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
