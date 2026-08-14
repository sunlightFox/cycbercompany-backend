package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UninstallPreflightToolTest {

    @Test
    void preflightAggregatesPrivilegePackageServiceAndProcessFacts() {
        PrivilegeTool privilegeTool = new PrivilegeTool((command, timeoutSeconds) -> new PrivilegeTool.CommandResult(
                0,
                new PrivilegeTool.CapturedOutput("""
                        {"accountName":"NT AUTHORITY\\\\SYSTEM","userSid":"S-1-5-18","isLocalSystem":true,"isAdministratorToken":true,"isPrivileged":true,"os":"Microsoft Windows 11"}
                        """, false),
                new PrivilegeTool.CapturedOutput("", false),
                false,
                10), true);

        SoftwareTool softwareTool = new SoftwareTool((command, timeoutSeconds) -> new SoftwareTool.CommandResult(
                0,
                new SoftwareTool.CapturedOutput("Tencent.QQ Tencent QQ 9.7.23.29392", false),
                new SoftwareTool.CapturedOutput("", false),
                false,
                11), true);

        ServiceTool serviceTool = new ServiceTool((command, environment, timeoutSeconds) -> new ServiceTool.CommandResult(
                0,
                new ServiceTool.CapturedOutput("""
                        {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Stopped","status":"OK","startMode":"Disabled","serviceType":"Win32OwnProcess","processId":0,"canStop":false}
                        """, false),
                new ServiceTool.CapturedOutput("", false),
                false,
                12), true);

        OsProcessTool osProcessTool = new OsProcessTool((command, environment, timeoutSeconds) -> new OsProcessTool.CommandResult(
                0,
                new OsProcessTool.CapturedOutput("""
                        {"processName":"QQPCTray.exe","count":0,"truncated":false,"processes":[]}
                        """, false),
                new OsProcessTool.CapturedOutput("", false),
                false,
                13), true);

        UninstallPreflightTool tool = new UninstallPreflightTool(privilegeTool, softwareTool, serviceTool, osProcessTool, true);

        var result = tool.preflight(Map.of(
                "packageId", "Tencent.QQ",
                "serviceName", "QQPCRTP",
                "processNames", List.of("QQPCTray.exe")));

        assertTrue(result.success());
        assertEquals(true, result.result().get("readyForUninstall"));
        assertEquals(true, result.result().get("isPrivileged"));
        assertEquals(true, result.result().get("packageInstalled"));
        assertEquals("Stopped", result.result().get("serviceState"));
        assertEquals(0, result.result().get("QQPCTray.exeCount"));
        assertTrue(((List<?>) result.result().get("blockingSignals")).isEmpty());
        assertFalse(((List<?>) result.result().get("recommendedNextSteps")).isEmpty());
    }

    @Test
    void preflightExplainsBlockingSignalsWhenFactsIndicateAProblem() {
        PrivilegeTool privilegeTool = new PrivilegeTool((command, timeoutSeconds) -> new PrivilegeTool.CommandResult(
                0,
                new PrivilegeTool.CapturedOutput("""
                        {"accountName":"user","userSid":"S-1-5-21","isLocalSystem":false,"isAdministratorToken":false,"isPrivileged":false,"os":"Microsoft Windows 11"}
                        """, false),
                new PrivilegeTool.CapturedOutput("", false),
                false,
                10), true);

        SoftwareTool softwareTool = new SoftwareTool((command, timeoutSeconds) -> new SoftwareTool.CommandResult(
                0,
                new SoftwareTool.CapturedOutput("Tencent.QQ Tencent QQ 9.7.23.29392", false),
                new SoftwareTool.CapturedOutput("", false),
                false,
                11), true);

        ServiceTool serviceTool = new ServiceTool((command, environment, timeoutSeconds) -> new ServiceTool.CommandResult(
                0,
                new ServiceTool.CapturedOutput("""
                        {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Running","status":"OK","startMode":"Auto","serviceType":"Win32OwnProcess","processId":1234,"canStop":false}
                        """, false),
                new ServiceTool.CapturedOutput("", false),
                false,
                12), true);

        OsProcessTool osProcessTool = new OsProcessTool((command, environment, timeoutSeconds) -> new OsProcessTool.CommandResult(
                0,
                new OsProcessTool.CapturedOutput("""
                        {"processName":"QQPCTray.exe","count":2,"truncated":false,"processes":[{"processId":4321,"name":"QQPCTray.exe","parentProcessId":100,"sessionId":1},{"processId":4322,"name":"QQPCTray.exe","parentProcessId":100,"sessionId":1}]}
                        """, false),
                new OsProcessTool.CapturedOutput("", false),
                false,
                13), true);

        UninstallPreflightTool tool = new UninstallPreflightTool(privilegeTool, softwareTool, serviceTool, osProcessTool, true);

        var result = tool.preflight(Map.of(
                "packageId", "Tencent.QQ",
                "serviceName", "QQPCRTP",
                "processNames", List.of("QQPCTray.exe")));

        assertTrue(result.success());
        assertFalse((Boolean) result.result().get("readyForUninstall"));
        assertTrue(((List<?>) result.result().get("blockingSignals")).size() >= 3);
        assertTrue(((List<?>) result.result().get("recommendedNextSteps")).size() >= 3);
    }

    @Test
    void preflightRejectsArgumentsThatWouldFailNestedStructuredTools() {
        AtomicInteger calls = new AtomicInteger();
        PrivilegeTool privilegeTool = new PrivilegeTool((command, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new PrivilegeTool.CommandResult(0, new PrivilegeTool.CapturedOutput("", false), new PrivilegeTool.CapturedOutput("", false), false, 1);
        }, true);
        SoftwareTool softwareTool = new SoftwareTool((command, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new SoftwareTool.CommandResult(0, new SoftwareTool.CapturedOutput("", false), new SoftwareTool.CapturedOutput("", false), false, 1);
        }, true);
        ServiceTool serviceTool = new ServiceTool((command, environment, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new ServiceTool.CommandResult(0, new ServiceTool.CapturedOutput("", false), new ServiceTool.CapturedOutput("", false), false, 1);
        }, true);
        OsProcessTool osProcessTool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new OsProcessTool.CommandResult(0, new OsProcessTool.CapturedOutput("", false), new OsProcessTool.CapturedOutput("", false), false, 1);
        }, true);
        UninstallPreflightTool tool = new UninstallPreflightTool(privilegeTool, softwareTool, serviceTool, osProcessTool, true);

        var packageResult = tool.preflight(Map.of("packageId", "Tencent QQ & del C:\\"));
        var serviceResult = tool.preflight(Map.of("packageId", "Tencent.QQ", "serviceName", "QQ Service"));
        var processResult = tool.preflight(Map.of("packageId", "Tencent.QQ", "processNames", List.of("C:\\Temp\\QQPCTray.exe")));

        assertFalse(packageResult.success());
        assertTrue(packageResult.errorMessage().contains("exact winget id"));
        assertFalse(serviceResult.success());
        assertTrue(serviceResult.errorMessage().contains("exact Windows service name"));
        assertFalse(processResult.success());
        assertTrue(processResult.errorMessage().contains("exact Windows image names"));
        assertEquals(0, calls.get());
    }

    @Test
    void nonWindowsNodesRejectTheCompositePreflight() {
        AtomicInteger calls = new AtomicInteger();
        PrivilegeTool privilegeTool = new PrivilegeTool((command, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new PrivilegeTool.CommandResult(0, new PrivilegeTool.CapturedOutput("", false), new PrivilegeTool.CapturedOutput("", false), false, 1);
        }, false);
        SoftwareTool softwareTool = new SoftwareTool((command, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new SoftwareTool.CommandResult(0, new SoftwareTool.CapturedOutput("", false), new SoftwareTool.CapturedOutput("", false), false, 1);
        }, false);
        ServiceTool serviceTool = new ServiceTool((command, environment, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new ServiceTool.CommandResult(0, new ServiceTool.CapturedOutput("", false), new ServiceTool.CapturedOutput("", false), false, 1);
        }, false);
        OsProcessTool osProcessTool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            calls.incrementAndGet();
            return new OsProcessTool.CommandResult(0, new OsProcessTool.CapturedOutput("", false), new OsProcessTool.CapturedOutput("", false), false, 1);
        }, false);
        UninstallPreflightTool tool = new UninstallPreflightTool(privilegeTool, softwareTool, serviceTool, osProcessTool, false);

        var result = tool.preflight(Map.of("packageId", "Tencent.QQ"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Windows only"));
        assertEquals(0, calls.get());
    }
}
