package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DesktopToolTest {

    @Test
    void executesWindowsWallpaperCommandForAnExistingImage() throws Exception {
        Path image = Files.createTempFile("agent-studio-wallpaper", ".jpg");
        AtomicReference<List<String>> command = new AtomicReference<>();
        DesktopTool tool = new DesktopTool(value -> {
            command.set(value);
            return new DesktopTool.CommandResult(0, "");
        }, "Windows 11");

        var result = tool.setWallpaper(Map.of("path", image.toString()));

        assertTrue(result.success());
        assertEquals(image.toRealPath().toString(), result.result().get("path"));
        assertEquals(true, result.result().get("applied"));
        assertEquals("powershell.exe", command.get().getFirst());
        assertTrue(command.get().contains("-EncodedCommand"));
    }

    @Test
    void rejectsNonWindowsNodesWithoutInvokingACommand() throws Exception {
        Path image = Files.createTempFile("agent-studio-wallpaper", ".png");
        DesktopTool tool = new DesktopTool(value -> {
            throw new AssertionError("The executor must not run on a non-Windows node.");
        }, "Linux");

        var result = tool.setWallpaper(Map.of("path", image.toString()));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("only on Windows"));
    }

    @Test
    void rejectsMissingAndUnsupportedImagesBeforeExecution() throws Exception {
        Path textFile = Files.createTempFile("agent-studio-wallpaper", ".txt");
        DesktopTool tool = new DesktopTool(value -> {
            throw new AssertionError("Invalid images must not reach PowerShell.");
        }, "Windows 11");

        var missing = tool.setWallpaper(Map.of("path", textFile.resolveSibling("missing.jpg").toString()));
        var unsupported = tool.setWallpaper(Map.of("path", textFile.toString()));

        assertFalse(missing.success());
        assertFalse(unsupported.success());
        assertTrue(unsupported.errorMessage().contains("JPG"));
    }
}
