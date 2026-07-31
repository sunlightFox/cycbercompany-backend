package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(read.result().get("path").toString().equals("src/Calculator.java"));
    }

    @Test
    void rejectsPathsOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-files");
        FileTool tool = new FileTool(workspace);

        var result = tool.write(Map.of("path", "../outside.txt", "content", "blocked"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("configured workspace"));
    }

    @Test
    void searchesSourceFilesWithRelativePathsAndLineNumbers() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-search");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/TaskController.java"), "class TaskController {\n  String marker = \"Needle\";\n}\n");
        Files.writeString(workspace.resolve("README.md"), "needle in documentation\n");
        Files.createDirectories(workspace.resolve("node_modules"));
        Files.writeString(workspace.resolve("node_modules/ignored.js"), "needle should be ignored\n");
        FileTool tool = new FileTool(workspace);

        var result = tool.search(Map.of("path", ".", "query", "needle"));

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        var matches = (java.util.List<Map<String, Object>>) result.result().get("matches");
        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(match -> "src/TaskController.java".equals(match.get("path")) && Integer.valueOf(2).equals(match.get("line"))));
        assertFalse(matches.stream().anyMatch(match -> match.get("path").toString().contains("node_modules")));
    }

    @Test
    void boundsSearchResultsAndKeepsSearchInsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-search");
        Files.writeString(workspace.resolve("matches.txt"), "match\nmatch\nmatch\n");
        FileTool tool = new FileTool(workspace);

        var bounded = tool.search(Map.of("path", ".", "query", "match", "maxResults", 2));
        var outside = tool.search(Map.of("path", "../", "query", "match"));

        assertTrue(bounded.success());
        @SuppressWarnings("unchecked")
        var matches = (java.util.List<Map<String, Object>>) bounded.result().get("matches");
        assertEquals(2, matches.size());
        assertTrue(Boolean.TRUE.equals(bounded.result().get("truncated")));
        assertFalse(outside.success());
        assertTrue(outside.errorMessage().contains("configured workspace"));
    }

    @Test
    void readsOnlyTheRequestedLineRangeFromALargeSourceFile() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-read-range");
        Files.writeString(workspace.resolve("Example.java"), "line one\nline two\nline three\nline four\n");
        FileTool tool = new FileTool(workspace);

        var result = tool.read(Map.of("path", "Example.java", "startLine", 2, "endLine", 3));

        assertTrue(result.success());
        assertTrue(result.result().get("content").toString().equals("line two\nline three"));
        assertTrue(result.result().get("path").toString().equals("Example.java"));
        assertTrue(Integer.valueOf(2).equals(result.result().get("startLine")));
        assertTrue(Integer.valueOf(3).equals(result.result().get("endLine")));
    }
}
