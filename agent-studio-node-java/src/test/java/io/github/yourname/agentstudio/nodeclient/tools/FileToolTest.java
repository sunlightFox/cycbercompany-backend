package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileToolTest {

    @Test
    void writesReadsAndPatchesAWorkspaceFile() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-files");
        FileTool tool = new FileTool(workspace);

        var write = tool.write(Map.of("path", "src/Calculator.java", "content", "return left - right;"));
        var patch = tool.applyPatch(Map.of(
                "path", "src/Calculator.java",
                "expected", "left - right",
                "replacement", "left + right"));
        var read = tool.read(Map.of("path", "src/Calculator.java"));

        assertTrue(write.success());
        assertTrue(patch.success());
        assertTrue(read.success());
        assertTrue(read.result().get("content").toString().contains("left + right"));
    }

    @Test
    void rejectsPathsOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-files");
        FileTool tool = new FileTool(workspace);

        var result = tool.write(Map.of("path", "../outside.txt", "content", "blocked"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("configured workspace"));
    }
}
