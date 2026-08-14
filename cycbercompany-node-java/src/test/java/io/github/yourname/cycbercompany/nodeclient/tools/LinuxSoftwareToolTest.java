package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LinuxSoftwareToolTest {

    @Test
    void installUsesRestrictedPackageServiceWithoutShellOrSudo() {
        LinuxSoftwareTool tool = new LinuxSoftwareTool((command, timeout) -> {
            throw new AssertionError("install must not invoke a local process");
        }, (request, allowUpgrade) -> {
            assertEquals("docker.io", request.packageName());
            assertEquals(600, request.timeoutSeconds());
            assertFalse(allowUpgrade);
            return new SoftwareTool.CommandResult(0,
                    new SoftwareTool.CapturedOutput("ok", false),
                    new SoftwareTool.CapturedOutput("", false), false, 8);
        });

        var result = tool.install(Map.of("packageId", "docker.io"));

        assertTrue(result.success());
        assertEquals("apt", result.result().get("manager"));
    }

    @Test
    void installPassesExplicitUpgradeApprovalToRestrictedPackageService() {
        LinuxSoftwareTool tool = new LinuxSoftwareTool((command, timeout) -> {
            throw new AssertionError("install must not invoke a local process");
        }, (request, allowUpgrade) -> {
            assertEquals("jq", request.packageName());
            assertTrue(allowUpgrade);
            return new SoftwareTool.CommandResult(0,
                    new SoftwareTool.CapturedOutput("ok", false),
                    new SoftwareTool.CapturedOutput("", false), false, 8);
        });

        var result = tool.install(Map.of("packageId", "jq", "allowUpgrade", true));

        assertTrue(result.success());
        assertEquals(true, result.result().get("allowUpgrade"));
    }

    @Test
    void rejectsFlagsPathsAndShellSyntaxInPackageNames() {
        LinuxSoftwareTool tool = new LinuxSoftwareTool((command, timeout) -> new SoftwareTool.CommandResult(0,
                new SoftwareTool.CapturedOutput("", false), new SoftwareTool.CapturedOutput("", false), false, 1));

        var result = tool.install(Map.of("packageId", "docker.io; touch /tmp/pwned"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("without paths"));
    }

    @Test
    void treatsDpkgMissingPackageAsNotInstalled() {
        LinuxSoftwareTool tool = new LinuxSoftwareTool((command, timeout) -> new SoftwareTool.CommandResult(1,
                new SoftwareTool.CapturedOutput("dpkg-query: no path found matching pattern", false),
                new SoftwareTool.CapturedOutput("", false), false, 1));

        var result = tool.query(Map.of("manager", "apt", "packageId", "docker.io"));

        assertTrue(result.success());
        assertEquals(false, result.result().get("installed"));
        assertEquals("NOT_INSTALLED", result.result().get("queryStatus"));
    }
}
