package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void snapshotsWindowsAndActivatesOnlyAnExplicitProcessId() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        DesktopTool tool = new DesktopTool(value -> {
            command.set(value);
            return new DesktopTool.CommandResult(0, "[{\"Id\":42,\"ProcessName\":\"notepad\"}]");
        }, "Windows 11");

        var snapshot = tool.sessionSnapshot(Map.of());
        long revision = ((Number) snapshot.result().get("snapshotRevision")).longValue();
        var activated = tool.activateWindow(Map.of("processId", 42, "snapshotRevision", revision));
        var invalid = tool.activateWindow(Map.of("processId", "not-an-id", "snapshotRevision", revision));

        assertTrue(snapshot.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> windows = (List<Map<String, Object>>) snapshot.result().get("windows");
        assertEquals(1, windows.size());
        assertEquals(42L, windows.getFirst().get("processId"));
        assertEquals("notepad", windows.getFirst().get("processName"));
        assertTrue(revision > 0);
        assertTrue(activated.success());
        assertEquals(42L, activated.result().get("processId"));
        assertTrue(command.get().contains("-EncodedCommand"));
        assertFalse(invalid.success());
    }

    @Test
    void startsOnlyFixedApprovedApplicationsAndRequiresASubsequentSnapshot() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        DesktopTool tool = new DesktopTool(value -> {
            command.set(value);
            return new DesktopTool.CommandResult(0, "{\"Id\":77,\"ProcessName\":\"notepad\"}");
        }, "Windows 11");

        var started = tool.startApprovedApplication(Map.of("application", "notepad"));
        var rejectedPath = tool.startApprovedApplication(Map.of("application", "C:\\Windows\\System32\\cmd.exe"));

        assertTrue(started.success());
        assertEquals(77L, started.result().get("processId"));
        assertEquals("notepad", started.result().get("application"));
        assertTrue(started.result().get("nextStep").toString().contains("session.snapshot"));
        assertTrue(command.get().contains("-EncodedCommand"));
        assertFalse(rejectedPath.success());
        assertTrue(rejectedPath.errorMessage().contains("Unsupported desktop application"));
    }

    @Test
    void capturesDesktopToAnArtifactRelativePathWithoutExposingTheNodePath() throws Exception {
        Path artifactRoot = Files.createTempDirectory("agent-studio-desktop-artifacts");
        DesktopTool tool = new DesktopTool(
                value -> new DesktopTool.CommandResult(0, ""),
                "Windows 11",
                artifactRoot,
                output -> {
                    // 测试替身只产生最小文件；真实节点使用 Robot 截取可见主显示器。
                    Files.createDirectories(output.getParent());
                    Files.write(output, new byte[] {1, 2, 3});
                    return new DesktopTool.ScreenCaptureResult(1920, 1080);
                });

        var result = tool.screenshot(Map.of());

        assertTrue(result.success());
        assertEquals("primary-display", result.result().get("capture"));
        assertEquals(1920, result.result().get("width"));
        assertEquals(1080, result.result().get("height"));
        assertEquals("desktop-screenshot", result.result().get("artifactType"));
        String relativePath = result.result().get("artifactPath").toString();
        assertTrue(relativePath.startsWith("desktop-screenshots/"));
        assertFalse(Path.of(relativePath).isAbsolute());
        assertTrue(Files.isRegularFile(artifactRoot.resolve(relativePath)));
        assertFalse(result.result().toString().contains(artifactRoot.toString()));
    }

    @Test
    void rejectsDesktopScreenshotOutsideWindowsBeforeCapturing() throws Exception {
        Path artifactRoot = Files.createTempDirectory("agent-studio-desktop-non-windows");
        DesktopTool tool = new DesktopTool(
                value -> new DesktopTool.CommandResult(0, ""),
                "Linux",
                artifactRoot,
                output -> {
                    throw new AssertionError("A non-Windows node must not capture the desktop.");
                });

        var result = tool.screenshot(Map.of());

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("only on Windows"));
    }

    @Test
    void requiresSnapshotBackedSelectorsForUiAutomationActions() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        DesktopTool tool = new DesktopTool(value -> {
            command.set(value);
            return new DesktopTool.CommandResult(
                    0,
                    "[{\"kind\":\"control\",\"processId\":42,\"automationId\":\"save-button\","
                            + "\"name\":\"Save\",\"controlType\":\"ControlType.Button\",\"enabled\":true}]");
        }, "Windows 11");

        var missingSelector = tool.uiClick(Map.of("processId", 42));
        var snapshot = tool.uiSnapshot(Map.of("processId", 42));
        var verified = tool.uiVerify(Map.of("processId", 42, "automationId", "save-button"));
        String firstRef = controlRef(snapshot);
        long firstRevision = ((Number) snapshot.result().get("snapshotRevision")).longValue();
        var clicked = tool.uiClick(Map.of("ref", firstRef, "snapshotRevision", firstRevision));
        var stale = tool.uiType(Map.of("ref", firstRef, "snapshotRevision", firstRevision, "text", "must fail"));
        var secondSnapshot = tool.uiSnapshot(Map.of("processId", 42));
        String secondRef = controlRef(secondSnapshot);
        long secondRevision = ((Number) secondSnapshot.result().get("snapshotRevision")).longValue();
        var typed = tool.uiType(Map.of("ref", secondRef, "snapshotRevision", secondRevision, "text", "updated"));

        assertFalse(missingSelector.success());
        assertTrue(snapshot.success());
        assertTrue(verified.success());
        assertEquals(true, verified.result().get("verified"));
        assertTrue(clicked.success());
        assertFalse(stale.success());
        assertTrue(typed.success());
        assertEquals(42L, clicked.result().get("processId"));
        assertTrue(command.get().contains("-EncodedCommand"));
    }

    @Test
    void returnsBoundedStructuredUiMetadataInsteadOfTruncatedJsonText() {
        String veryLongName = "n".repeat(300);
        DesktopTool tool = new DesktopTool(value -> new DesktopTool.CommandResult(
                0,
                "[{\"kind\":\"control\",\"processId\":42,\"automationId\":\"save\",\"name\":\""
                        + veryLongName + "\",\"controlType\":\"ControlType.Button\",\"enabled\":true}]"), "Windows 11");

        var snapshot = tool.uiSnapshot(Map.of("processId", 42));

        assertTrue(snapshot.success());
        assertFalse(snapshot.result().containsKey("controlsJson"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> controls = (List<Map<String, Object>>) snapshot.result().get("controls");
        assertEquals(1, controls.size());
        assertEquals(42L, controls.getFirst().get("processId"));
        assertEquals("save", controls.getFirst().get("automationId"));
        assertEquals(240, controls.getFirst().get("name").toString().length());
        assertEquals(true, controls.getFirst().get("enabled"));
        assertTrue(controls.getFirst().get("ref").toString().startsWith("ui-"));
    }

    @SuppressWarnings("unchecked")
    private static String controlRef(io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult snapshot) {
        assertTrue(snapshot.success());
        List<Map<String, Object>> controls = (List<Map<String, Object>>) snapshot.result().get("controls");
        return controls.getFirst().get("ref").toString();
    }

    @Test
    void readsOnlyTheBoundedValueReturnedByWindowsUiAutomation() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        DesktopTool tool = new DesktopTool(value -> {
            command.set(value);
            return new DesktopTool.CommandResult(0, "{\"value\":\"已保存的任务\",\"truncated\":false}");
        }, "Windows 11");

        var result = tool.uiReadValue(Map.of("processId", 42, "automationId", "task-title"));

        assertTrue(result.success());
        assertEquals(true, result.result().get("read"));
        assertEquals("已保存的任务", result.result().get("value"));
        assertEquals(false, result.result().get("truncated"));
        assertTrue(command.get().contains("-EncodedCommand"));
    }

    @Test
    void waitsForAnAsynchronouslyAppearingUiControlWithoutActingOnIt() {
        AtomicInteger attempts = new AtomicInteger();
        DesktopTool tool = new DesktopTool(value -> new DesktopTool.CommandResult(
                attempts.incrementAndGet() == 1 ? 1 : 0,
                attempts.get() == 1 ? "control not found yet" : "{\"exists\":true}"), "Windows 11");

        var result = tool.uiWait(Map.of(
                "processId", 42,
                "automationId", "save-button",
                "timeoutMs", 1_000));

        assertTrue(result.success());
        assertEquals(true, result.result().get("available"));
        assertEquals(2, result.result().get("attempts"));
        assertTrue(((Number) result.result().get("waitedMs")).longValue() >= 0);
        assertTrue(result.result().get("note").toString().contains("ui.snapshot again"));
    }

    @Test
    void rejectsDesktopUiWaitTimeoutOutsideItsBoundedRange() {
        DesktopTool tool = new DesktopTool(value -> {
            throw new AssertionError("An invalid wait timeout must be rejected before PowerShell runs.");
        }, "Windows 11");

        var result = tool.uiWait(Map.of("processId", 42, "automationId", "save-button", "timeoutMs", 99));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("timeoutMs must be between"));
    }

    @Test
    void rejectsUiValueReadsOutsideWindowsBeforeInvokingTheExecutor() {
        DesktopTool tool = new DesktopTool(value -> {
            throw new AssertionError("Non-Windows nodes must not invoke UI Automation.");
        }, "Linux");

        var result = tool.uiReadValue(Map.of("processId", 42, "automationId", "task-title"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("only on Windows"));
    }

    @Test
    void validatesAndExecutesKeyboardAndClipboardOperations() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicInteger invocation = new AtomicInteger();
        DesktopTool tool = new DesktopTool(value -> {
            command.set(value);
            return new DesktopTool.CommandResult(0,
                    invocation.incrementAndGet() == 1
                            ? "[{\"Id\":42,\"ProcessName\":\"notepad\"}]"
                            : "copied text");
        }, "Windows 11");

        var snapshot = tool.sessionSnapshot(Map.of());
        long revision = ((Number) snapshot.result().get("snapshotRevision")).longValue();
        var keyboard = tool.keyboardPress(Map.of("processId", 42, "snapshotRevision", revision, "keys", "{ENTER}"));
        var read = tool.clipboardGet(Map.of());
        var write = tool.clipboardSet(Map.of("text", "hello"));
        var invalid = tool.keyboardPress(Map.of("processId", "not-a-process", "snapshotRevision", revision, "keys", "{ENTER}"));

        assertTrue(keyboard.success());
        assertTrue(read.success());
        assertEquals("copied text", read.result().get("text"));
        assertTrue(write.success());
        assertFalse(invalid.success());
        assertTrue(command.get().contains("-EncodedCommand"));
    }

    @Test
    void rejectsGuessedAndStaleProcessIdsForWindowSideEffects() {
        DesktopTool tool = new DesktopTool(value -> new DesktopTool.CommandResult(
                0, "[{\"Id\":42,\"ProcessName\":\"notepad\"}]"), "Windows 11");

        var first = tool.sessionSnapshot(Map.of());
        long firstRevision = ((Number) first.result().get("snapshotRevision")).longValue();
        var guessed = tool.activateWindow(Map.of("processId", 99, "snapshotRevision", firstRevision));
        var second = tool.sessionSnapshot(Map.of());
        var stale = tool.keyboardPress(Map.of(
                "processId", 42, "snapshotRevision", firstRevision, "keys", "{ENTER}"));

        assertFalse(guessed.success());
        assertTrue(guessed.errorMessage().contains("not present"));
        assertTrue(second.success());
        assertFalse(stale.success());
        assertTrue(stale.errorMessage().contains("stale"));
    }
}
