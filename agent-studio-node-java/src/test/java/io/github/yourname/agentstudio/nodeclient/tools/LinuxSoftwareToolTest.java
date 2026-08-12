package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LinuxSoftwareToolTest {

    @Test
    void installUsesAptWithoutShellOrInteractivePrompt() {
        AtomicReference<List<String>> observed = new AtomicReference<>();
        LinuxSoftwareTool tool = new LinuxSoftwareTool((command, timeout) -> {
            observed.set(command);
            return new SoftwareTool.CommandResult(0,
                    new SoftwareTool.CapturedOutput("ok", false),
                    new SoftwareTool.CapturedOutput("", false), false, 8);
        });

        var result = tool.install(Map.of("packageId", "docker.io"));

        assertTrue(result.success());
        assertEquals(List.of("sudo", "-n", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y",
                "--no-install-recommends", "--no-upgrade", "docker.io"), observed.get());
        assertEquals("apt", result.result().get("manager"));
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
