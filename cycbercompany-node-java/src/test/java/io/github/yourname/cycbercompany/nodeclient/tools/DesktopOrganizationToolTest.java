package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesktopOrganizationToolTest {

    @Test
    void listsAndMovesOnlyVisibleTopLevelDesktopFiles() throws Exception {
        Path desktop = Files.createTempDirectory("cycbercompany-desktop");
        Files.writeString(desktop.resolve("inbox.txt"), "sort me");
        Files.createDirectory(desktop.resolve("Existing"));
        DesktopOrganizationTool tool = new DesktopOrganizationTool(desktop);

        var listed = tool.list(Map.of());
        var category = tool.createCategory(Map.of("category", "Documents"));
        var moved = tool.move(Map.of("source", "inbox.txt", "category", "Documents"));

        assertTrue(listed.success());
        assertEquals(desktop.toRealPath().toString(), listed.result().get("desktopPath"));
        assertEquals(1, listed.result().get("sortableFiles"));
        assertEquals(java.util.List.of("Existing"), listed.result().get("visibleDirectories"));
        assertTrue(category.success());
        assertTrue(moved.success());
        assertFalse(Files.exists(desktop.resolve("inbox.txt")));
        assertTrue(Files.exists(desktop.resolve("Documents/inbox.txt")));
    }

    @Test
    void rejectsPathsDirectoriesAndExternalTargets() throws Exception {
        Path desktop = Files.createTempDirectory("cycbercompany-desktop");
        Path outside = Files.createTempDirectory("cycbercompany-outside");
        Files.writeString(outside.resolve("private.txt"), "keep");
        Files.createDirectory(desktop.resolve("Folder"));
        DesktopOrganizationTool tool = new DesktopOrganizationTool(desktop);

        var traversal = tool.move(Map.of("source", "../private.txt", "category", "Documents"));
        var directory = tool.move(Map.of("source", "Folder", "category", "Documents"));
        var absoluteCategory = tool.createCategory(Map.of("category", outside.toString()));

        assertFalse(traversal.success());
        assertFalse(directory.success());
        assertFalse(absoluteCategory.success());
        assertTrue(Files.exists(outside.resolve("private.txt")));
        assertTrue(Files.exists(desktop.resolve("Folder")));
    }

    @Test
    void deletesOnlyAVisibleTopLevelRegularFile() throws Exception {
        Path desktop = Files.createTempDirectory("cycbercompany-desktop");
        Files.writeString(desktop.resolve("delete-me.txt"), "remove");
        Files.createDirectory(desktop.resolve("Folder"));
        DesktopOrganizationTool tool = new DesktopOrganizationTool(desktop);

        var deleted = tool.delete(Map.of("source", "delete-me.txt"));
        var directory = tool.delete(Map.of("source", "Folder"));
        var traversal = tool.delete(Map.of("source", "../outside.txt"));

        assertTrue(deleted.success());
        assertFalse(Files.exists(desktop.resolve("delete-me.txt")));
        assertFalse(directory.success());
        assertTrue(directory.errorMessage().contains("does not delete directories"));
        assertTrue(directory.errorMessage().contains("system.fs.delete"));
        assertFalse(traversal.success());
        assertTrue(Files.isDirectory(desktop.resolve("Folder")));
    }

    @Test
    void createsANewUtf8TextFileWithAUnicodeFilenameWithoutAcceptingPathsOrOverwrite() throws Exception {
        Path desktop = Files.createTempDirectory("cycbercompany-desktop");
        DesktopOrganizationTool tool = new DesktopOrganizationTool(desktop);

        var created = tool.write(Map.of("filename", "\u9759\u591c\u601d.txt", "content", "\u5e8a\u524d\u660e\u6708\u5149"));
        var overwrite = tool.write(Map.of("filename", "\u9759\u591c\u601d.txt", "content", "changed"));
        var nested = tool.write(Map.of("filename", "notes/secret.txt", "content", "no"));

        assertTrue(created.success());
        assertEquals("\u5e8a\u524d\u660e\u6708\u5149", Files.readString(desktop.resolve("\u9759\u591c\u601d.txt")));
        assertFalse(overwrite.success());
        assertFalse(nested.success());
    }

    @Test
    void rejectsUnreplacedFilenameAndCategoryPlaceholdersBeforeResolvingPaths() throws Exception {
        Path desktop = Files.createTempDirectory("cycbercompany-desktop-placeholder");
        DesktopOrganizationTool tool = new DesktopOrganizationTool(desktop);

        var write = tool.write(Map.of("filename", "<path>", "content", "blocked"));
        var category = tool.createCategory(Map.of("category", "<folder>"));

        assertFalse(write.success());
        assertTrue(write.errorMessage().contains("unreplaced placeholder"));
        assertFalse(category.success());
        assertTrue(category.errorMessage().contains("unreplaced placeholder"));
    }
}
