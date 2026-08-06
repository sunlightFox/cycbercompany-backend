package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServiceToolTest {

    @Test
    void queryUsesFixedPowerShellAndAnEnvironmentBoundServiceName() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        AtomicReference<Map<String, String>> observedEnvironment = new AtomicReference<>();
        ServiceTool tool = new ServiceTool((command, environment, timeoutSeconds) -> {
            observedCommand.set(command);
            observedEnvironment.set(environment);
            return new ServiceTool.CommandResult(
                    0,
                    new ServiceTool.CapturedOutput("""
                            {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Running","status":"OK","startMode":"Auto","serviceType":"Win32OwnProcess","processId":1234,"canStop":false}
                            """, false),
                    new ServiceTool.CapturedOutput("", false),
                    false,
                    21);
        }, true);

        var result = tool.query(Map.of("serviceName", "QQPCRTP"));

        assertTrue(result.success());
        assertEquals(List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command"), observedCommand.get().subList(0, 6));
        assertEquals("QQPCRTP", observedEnvironment.get().get("AGENT_STUDIO_SERVICE_NAME"));
        assertEquals("QQPCRTP", ((Map<?, ?>) result.result().get("service")).get("name"));
    }

    @Test
    void stopUsesTheSameControlledEntryPoint() {
        AtomicReference<Map<String, String>> observedEnvironment = new AtomicReference<>();
        ServiceTool tool = new ServiceTool((command, environment, timeoutSeconds) -> {
            observedEnvironment.set(environment);
            return new ServiceTool.CommandResult(
                    0,
                    new ServiceTool.CapturedOutput("""
                            {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Stopped","status":"OK","startMode":"Disabled","serviceType":"Win32OwnProcess","processId":0,"canStop":false}
                            """, false),
                    new ServiceTool.CapturedOutput("", false),
                    false,
                    19);
        }, true);

        var result = tool.stop(Map.of("serviceName", "QQPCRTP"));

        assertTrue(result.success());
        assertEquals("QQPCRTP", observedEnvironment.get().get("AGENT_STUDIO_SERVICE_NAME"));
        assertEquals("Stopped", ((Map<?, ?>) result.result().get("service")).get("state"));
    }

    @Test
    void setStartModeNormalizesDisabledAndReturnsSnapshot() {
        AtomicReference<Map<String, String>> observedEnvironment = new AtomicReference<>();
        ServiceTool tool = new ServiceTool((command, environment, timeoutSeconds) -> {
            observedEnvironment.set(environment);
            return new ServiceTool.CommandResult(
                    0,
                    new ServiceTool.CapturedOutput("""
                            {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Stopped","status":"OK","startMode":"Disabled","serviceType":"Win32OwnProcess","processId":0,"canStop":false}
                            """, false),
                    new ServiceTool.CapturedOutput("", false),
                    false,
                    17);
        }, true);

        var result = tool.setStartMode(Map.of("serviceName", "QQPCRTP", "startMode", "disabled"));

        assertTrue(result.success());
        assertEquals("Disabled", observedEnvironment.get().get("AGENT_STUDIO_SERVICE_START_MODE"));
        assertEquals("Disabled", result.result().get("requestedStartMode"));
    }

    @Test
    void rejectsUnsafeServiceNamesBeforeStartingPowerShell() {
        AtomicBoolean called = new AtomicBoolean(false);
        ServiceTool tool = new ServiceTool((command, environment, timeoutSeconds) -> {
            called.set(true);
            return new ServiceTool.CommandResult(
                    0,
                    new ServiceTool.CapturedOutput("", false),
                    new ServiceTool.CapturedOutput("", false),
                    false,
                    1);
        }, true);

        var result = tool.query(Map.of("serviceName", "QQPCRTP && del C:\\"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("exact Windows service name"));
        assertFalse(called.get());
    }

    @Test
    void nonWindowsNodesRejectServiceOperations() {
        AtomicBoolean called = new AtomicBoolean(false);
        ServiceTool tool = new ServiceTool((command, environment, timeoutSeconds) -> {
            called.set(true);
            return new ServiceTool.CommandResult(
                    0,
                    new ServiceTool.CapturedOutput("", false),
                    new ServiceTool.CapturedOutput("", false),
                    false,
                    1);
        }, false);

        var result = tool.stop(Map.of("serviceName", "QQPCRTP"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Windows services only"));
        assertFalse(called.get());
    }
}
