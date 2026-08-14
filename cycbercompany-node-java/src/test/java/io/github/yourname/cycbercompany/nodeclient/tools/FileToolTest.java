package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileToolTest {

    @Test
    void writesReadsAndPatchesAWorkspaceFile() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-files");
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
        assertTrue(read.result().get("digest").toString().startsWith("sha256:"));
    }

    @Test
    void rejectsAStaleDigestBeforeOverwritingUserChanges() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-digest");
        Files.writeString(workspace.resolve("Config.java"), "int port = 8080;");
        FileTool tool = new FileTool(workspace);
        String digest = tool.read(Map.of("path", "Config.java")).result().get("digest").toString();
        Files.writeString(workspace.resolve("Config.java"), "int port = 9090;");

        var result = tool.applyPatch(Map.of(
                "path", "Config.java", "expected", "9090", "replacement", "8081", "expectedDigest", digest));

        assertFalse(result.success());
        assertTrue(Files.readString(workspace.resolve("Config.java")).contains("9090"));
    }

    @Test
    void singleFileWritesAndPatchesKeepTheOriginalFileWhenReplacementFails() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-staged-single-file");
        Path source = workspace.resolve("Config.java");
        Files.writeString(source, "int port = 8080;");
        // 用一个在真正替换前失败的移动器模拟文件系统瞬时故障。只要暂存流程正确，
        // 目标文件不应被截断，也不应留下节点工具自己的临时文件。
        FileTool tool = new FileTool(workspace, false, (staged, target) -> {
            throw new IOException("simulated replacement failure");
        });

        var write = tool.write(Map.of("path", "Config.java", "content", "int port = 9090;"));
        var patch = tool.applyPatch(Map.of(
                "path", "Config.java", "expected", "8080", "replacement", "8081"));

        assertFalse(write.success());
        assertFalse(patch.success());
        assertEquals("int port = 8080;", Files.readString(source));
        try (var entries = Files.list(workspace)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".cycbercompany-")));
        }
    }

    @Test
    void batchPatchValidatesEveryFileBeforeWritingAnyOfThem() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-batch-patch");
        Files.writeString(workspace.resolve("A.java"), "class A { int value = 1; }");
        Files.writeString(workspace.resolve("B.java"), "class B { int value = 2; }");
        FileTool tool = new FileTool(workspace);

        var rejected = tool.applyPatchBatch(Map.of("changes", java.util.List.of(
                Map.of("path", "A.java", "expected", "value = 1", "replacement", "value = 10"),
                Map.of("path", "B.java", "expected", "missing", "replacement", "value = 20"))));
        assertFalse(rejected.success());
        assertTrue(Files.readString(workspace.resolve("A.java")).contains("value = 1"));

        var applied = tool.applyPatchBatch(Map.of("changes", java.util.List.of(
                Map.of("path", "A.java", "expected", "value = 1", "replacement", "value = 10"),
                Map.of("path", "B.java", "expected", "value = 2", "replacement", "value = 20"))));
        assertTrue(applied.success());
        assertTrue(Files.readString(workspace.resolve("A.java")).contains("value = 10"));
        assertTrue(Files.readString(workspace.resolve("B.java")).contains("value = 20"));
    }

    @Test
    void batchPatchRestoresEarlierFilesWhenALaterReplacementFails() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-batch-rollback");
        Path first = workspace.resolve("A.java");
        Path second = workspace.resolve("B.java");
        Files.writeString(first, "class A { int value = 1; }");
        Files.writeString(second, "class B { int value = 2; }");
        int[] moveCount = {0};
        FileTool tool = new FileTool(workspace, false, (source, target) -> {
            // 第二次替换模拟短暂 I/O 失败；第三次移动是第一份文件的回滚，应该能继续执行。
            if (++moveCount[0] == 2) {
                throw new IOException("simulated replacement failure");
            }
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });

        var result = tool.applyPatchBatch(Map.of("changes", java.util.List.of(
                Map.of("path", "A.java", "expected", "value = 1", "replacement", "value = 10"),
                Map.of("path", "B.java", "expected", "value = 2", "replacement", "value = 20"))));

        assertFalse(result.success());
        assertTrue(Files.readString(first).contains("value = 1"));
        assertTrue(Files.readString(second).contains("value = 2"));
        assertTrue(Boolean.TRUE.equals(result.result().get("rollbackAttempted")));
        assertTrue(Boolean.TRUE.equals(result.result().get("rollbackSucceeded")));
    }

    @Test
    void batchPatchCanApplyOrderedChangesToDifferentPartsOfTheSameFile() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-same-file-batch");
        Path source = workspace.resolve("Calculator.java");
        Files.writeString(source, "class Calculator { int add = 1; int subtract = 2; }");
        FileTool tool = new FileTool(workspace);

        var result = tool.applyPatchBatch(Map.of("changes", java.util.List.of(
                Map.of("path", "Calculator.java", "expected", "add = 1", "replacement", "add = 10"),
                Map.of("path", "Calculator.java", "expected", "subtract = 2", "replacement", "subtract = 20"))));

        assertTrue(result.success());
        assertTrue(Files.readString(source).contains("add = 10; int subtract = 20"));
        assertEquals(2, result.result().get("replacements"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> changed = (java.util.List<Map<String, Object>>) result.result().get("changed");
        assertEquals(1, changed.size());
    }

    @Test
    void rejectsPathsOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-files");
        FileTool tool = new FileTool(workspace);

        var result = tool.write(Map.of("path", "../outside.txt", "content", "blocked"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("configured workspace"));
    }

    @Test
    void rejectsUnreplacedPathPlaceholdersBeforeResolvingPaths() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-placeholder-path");
        FileTool tool = new FileTool(workspace, true);

        var mkdir = tool.createDirectory(Map.of("path", "<path>"));
        var write = tool.write(Map.of("path", "<path>/status.txt", "content", "blocked"));

        assertFalse(mkdir.success());
        assertFalse(write.success());
        assertTrue(mkdir.errorMessage().contains("unreplaced placeholder"));
        assertTrue(write.errorMessage().contains("unreplaced placeholder"));
    }

    @Test
    void searchesSourceFilesWithRelativePathsAndLineNumbers() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-search");
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
        Path workspace = Files.createTempDirectory("cycbercompany-node-search");
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
        Path workspace = Files.createTempDirectory("cycbercompany-node-read-range");
        Files.writeString(workspace.resolve("Example.java"), "line one\nline two\nline three\nline four\n");
        FileTool tool = new FileTool(workspace);

        var result = tool.read(Map.of("path", "Example.java", "startLine", 2, "endLine", 3));

        assertTrue(result.success());
        assertTrue(result.result().get("content").toString().equals("line two\nline three"));
        assertTrue(result.result().get("path").toString().equals("Example.java"));
        assertTrue(Integer.valueOf(2).equals(result.result().get("startLine")));
        assertTrue(Integer.valueOf(3).equals(result.result().get("endLine")));
    }

    @Test
    void systemAccessCanOrganizeFilesOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("cycbercompany-node-files");
        Path outside = Files.createTempDirectory("cycbercompany-node-system-files");
        Files.writeString(outside.resolve("inbox.txt"), "sort me");
        FileTool tool = new FileTool(workspace, true);

        var mkdir = tool.createDirectory(Map.of("path", outside.resolve("Documents").toString()));
        var move = tool.move(Map.of(
                "source", outside.resolve("inbox.txt").toString(),
                "destination", outside.resolve("Documents/inbox.txt").toString()));
        var delete = tool.delete(Map.of("path", outside.resolve("Documents/inbox.txt").toString()));

        assertTrue(mkdir.success());
        assertTrue(move.success());
        assertTrue(delete.success());
        assertFalse(Files.exists(outside.resolve("Documents/inbox.txt")));
    }
}
