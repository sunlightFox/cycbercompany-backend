package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SoftwareToolTest {

    @Test
    void listUsesBoundedWingetInventoryWithoutSearchArguments() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            observedCommand.set(command);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("Name Id Version\nVS Code Microsoft.VisualStudioCode 1.100", false),
                    new SoftwareTool.CapturedOutput("", false), false, 12);
        }, true);

        var result = tool.list(Map.of("timeoutSeconds", "10"));

        assertTrue(result.success());
        assertEquals(List.of("winget", "list", "--accept-source-agreements", "--disable-interactivity"), observedCommand.get());
        assertEquals("list", result.result().get("operation"));
        assertTrue(result.result().get("limitations").toString().contains("winget"));
    }

    @Test
    void queryUsesExactWingetIdWithoutShell() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        AtomicInteger observedTimeout = new AtomicInteger();
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            observedCommand.set(command);
            observedTimeout.set(timeoutSeconds);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("Tencent QQ Tencent.QQ 9.7.23.29392", false),
                    new SoftwareTool.CapturedOutput("", false),
                    false,
                    12);
        }, true);

        var result = tool.query(Map.of("packageId", "Tencent.QQ", "timeoutSeconds", "10"));

        assertTrue(result.success());
        assertEquals(List.of(
                "winget",
                "list",
                "--id",
                "Tencent.QQ",
                "--exact",
                "--accept-source-agreements",
                "--disable-interactivity"), observedCommand.get());
        assertEquals(10, observedTimeout.get());
        assertEquals(true, result.result().get("installed"));
    }

    @Test
    void queryDoesNotTreatPackageIdSubstringsAsInstalled() {
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> new SoftwareTool.CommandResult(
                0,
                new SoftwareTool.CapturedOutput("Tencent QQ Music Tencent.QQMusic 1.2.3", false),
                new SoftwareTool.CapturedOutput("", false),
                false,
                12), true);

        var result = tool.query(Map.of("packageId", "Tencent.QQ"));

        assertTrue(result.success());
        assertEquals(false, result.result().get("exactPackageIdPresent"));
        assertEquals(false, result.result().get("installed"));
    }

    @Test
    void queryTreatsWingetNoApplicationsFoundAsNotInstalled() {
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> new SoftwareTool.CommandResult(
                0x8A150014,
                new SoftwareTool.CapturedOutput("No installed package found matching input criteria.", false),
                new SoftwareTool.CapturedOutput("", false),
                false,
                12), true);

        var result = tool.query(Map.of("packageId", "Tencent.QQ"));

        assertTrue(result.success());
        assertEquals(false, result.result().get("installed"));
        assertEquals("NOT_INSTALLED", result.result().get("queryStatus"));
        assertEquals("0x8A150014", result.result().get("exitCodeHex"));
    }

    @Test
    void uninstallUsesSilentExactWingetCommandWithBoundedTimeout() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        AtomicInteger observedTimeout = new AtomicInteger();
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            observedCommand.set(command);
            observedTimeout.set(timeoutSeconds);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("Uninstalled", false),
                    new SoftwareTool.CapturedOutput("", false),
                    false,
                    34);
        }, true);

        var result = tool.uninstall(Map.of("packageId", "Tencent.QQ", "timeoutSeconds", "9999"));

        assertTrue(result.success());
        assertEquals(List.of(
                "winget",
                "uninstall",
                "--id",
                "Tencent.QQ",
                "--exact",
                "--silent",
                "--accept-source-agreements",
                "--disable-interactivity"), observedCommand.get());
        assertEquals(600, observedTimeout.get());
        assertEquals("Does not bypass Windows ACLs, protected services, running file locks, vendor uninstallers, or reboot requirements.",
                result.result().get("limitations"));
    }

    @Test
    void uninstallFailureGuidesTheModelTowardTheCompositePreflight() {
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> new SoftwareTool.CommandResult(
                1,
                new SoftwareTool.CapturedOutput("blocked", false),
                new SoftwareTool.CapturedOutput("", false),
                false,
                44), true);

        var result = tool.uninstall(Map.of("packageId", "Tencent.QQ"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("system.uninstall.preflight"));
    }

    @Test
    void installUsesExactWingetIdAndDoesNotUpgradeByDefault() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        AtomicInteger observedTimeout = new AtomicInteger();
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            observedCommand.set(command);
            observedTimeout.set(timeoutSeconds);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("Installed", false),
                    new SoftwareTool.CapturedOutput("", false),
                    false,
                    55);
        }, true);

        var result = tool.install(Map.of(
                "packageId", "Microsoft.VisualStudioCode",
                "source", "winget",
                "scope", "user",
                "version", "1.100.0"));

        assertTrue(result.success());
        assertEquals(List.of(
                "winget",
                "install",
                "--id",
                "Microsoft.VisualStudioCode",
                "--exact",
                "--source",
                "winget",
                "--scope",
                "user",
                "--version",
                "1.100.0",
                "--silent",
                "--no-upgrade",
                "--accept-package-agreements",
                "--accept-source-agreements",
                "--disable-interactivity"), observedCommand.get());
        assertEquals(600, observedTimeout.get());
        assertEquals(false, result.result().get("allowUpgrade"));
        assertEquals("winget", result.result().get("source"));
        assertEquals("user", result.result().get("scope"));
    }

    @Test
    void installCanExplicitlyAllowUpgradeWithoutExposingForceOrCustomArguments() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            observedCommand.set(command);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("Installed", false),
                    new SoftwareTool.CapturedOutput("", false),
                    false,
                    55);
        }, true);

        var result = tool.install(Map.of(
                "packageId", "Microsoft.PowerToys",
                "allowUpgrade", true,
                "silent", false,
                "timeoutSeconds", "2400"));

        assertTrue(result.success());
        assertEquals(List.of(
                "winget",
                "install",
                "--id",
                "Microsoft.PowerToys",
                "--exact",
                "--accept-package-agreements",
                "--accept-source-agreements",
                "--disable-interactivity"), observedCommand.get());
        assertEquals(1200, result.result().get("timeoutSeconds"));
        assertEquals(true, result.result().get("allowUpgrade"));
        assertEquals(false, result.result().get("silent"));
    }

    @Test
    void installExplainsStaleManifestDownloadUrlInsteadOfBlamingElevation() {
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> new SoftwareTool.CommandResult(
                0x80190194,
                new SoftwareTool.CapturedOutput("Download request status is not success. Not found (404).", false),
                new SoftwareTool.CapturedOutput("", false),
                false,
                55), true);

        var result = tool.install(Map.of("packageId", "Tencent.QQ"));

        assertFalse(result.success());
        assertEquals("PACKAGE_DOWNLOAD_NOT_FOUND", result.result().get("failureCategory"));
        assertEquals("Tencent.QQ.NT", result.result().get("suggestedPackageId"));
        assertEquals("0x80190194", result.result().get("exitCodeHex"));
        assertTrue(result.errorMessage().contains("HTTP 404"));
        assertTrue(result.errorMessage().contains("not an elevation"));
        assertTrue(result.errorMessage().contains("Tencent.QQ.NT"));
    }

    @Test
    void rejectsDisplayNamesAndShellSyntaxBeforeRunningWinget() {
        AtomicBoolean called = new AtomicBoolean(false);
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            called.set(true);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("", false),
                    new SoftwareTool.CapturedOutput("", false),
                    false,
                    1);
        }, true);

        var result = tool.uninstall(Map.of("packageId", "Tencent QQ & del C:\\"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("exact winget id"));
        assertFalse(called.get());
    }

    @Test
    void installRejectsUnsafeOptionalFieldsBeforeRunningWinget() {
        AtomicBoolean called = new AtomicBoolean(false);
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            called.set(true);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("", false),
                    new SoftwareTool.CapturedOutput("", false),
                    false,
                    1);
        }, true);

        var result = tool.install(Map.of(
                "packageId", "Microsoft.VisualStudioCode",
                "version", "1.0 && calc"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("literal winget version"));
        assertFalse(called.get());
    }

    @Test
    void nonWindowsNodesFailGracefullyWithoutRunningWinget() {
        AtomicBoolean called = new AtomicBoolean(false);
        SoftwareTool tool = new SoftwareTool((command, timeoutSeconds) -> {
            called.set(true);
            return new SoftwareTool.CommandResult(
                    0,
                    new SoftwareTool.CapturedOutput("", false),
                    new SoftwareTool.CapturedOutput("", false),
                    false,
                    1);
        }, false);

        var result = tool.query(Map.of("packageId", "Tencent.QQ"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Windows winget only"));
        assertFalse(called.get());
    }
}
