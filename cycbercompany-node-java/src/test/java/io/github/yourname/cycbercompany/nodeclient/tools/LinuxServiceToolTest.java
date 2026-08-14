package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LinuxServiceToolTest {

    @Test
    void queryUsesExactSystemctlUnitWithoutShell() {
        AtomicReference<List<String>> observed = new AtomicReference<>();
        LinuxServiceTool tool = new LinuxServiceTool((command, timeout) -> {
            observed.set(command);
            return result(0);
        });

        var execution = tool.query(Map.of("serviceName", "docker.service"));

        assertTrue(execution.success());
        assertEquals(List.of("systemctl", "show", "docker.service", "--no-page",
                "--property=Id,Description,LoadState,ActiveState,SubState,UnitFileState,MainPID,CanStop"), observed.get());
    }

    @Test
    void stopUsesPasswordlessSudoWithExactUnit() {
        AtomicReference<List<String>> observed = new AtomicReference<>();
        LinuxServiceTool tool = new LinuxServiceTool((command, timeout) -> {
            observed.set(command);
            return result(0);
        });

        assertTrue(tool.stop(Map.of("serviceName", "docker.service")).success());
        assertEquals(List.of("sudo", "-n", "systemctl", "stop", "docker.service"), observed.get());
    }

    @Test
    void restartUsesTheRestrictedServiceControlSocket() {
        LinuxServiceTool tool = new LinuxServiceTool((command, timeout) -> result(0), request -> result(0));

        assertTrue(tool.restart(Map.of("serviceName", "agent-studio-node.service")).success());
    }

    @Test
    void rejectsServiceNameInjection() {
        LinuxServiceTool tool = new LinuxServiceTool((command, timeout) -> result(0));

        var execution = tool.stop(Map.of("serviceName", "docker.service; touch /tmp/pwned"));

        assertFalse(execution.success());
        assertTrue(execution.errorMessage().contains("without paths"));
    }

    private static SoftwareTool.CommandResult result(int exitCode) {
        return new SoftwareTool.CommandResult(exitCode,
                new SoftwareTool.CapturedOutput("Id=docker.service", false),
                new SoftwareTool.CapturedOutput("", false), false, 1);
    }
}
