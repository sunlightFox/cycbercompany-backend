package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UninstallWorkflowToolTest {

    @Test
    void executeAcceptsVerifiedAbsenceAfterUninstallCommandFailure() {
        PrivilegeTool privilegeTool = new PrivilegeTool((command, timeoutSeconds) -> new PrivilegeTool.CommandResult(
                0,
                new PrivilegeTool.CapturedOutput("{\"isPrivileged\":true}", false),
                new PrivilegeTool.CapturedOutput("", false),
                false,
                1), true);
        AtomicInteger listCalls = new AtomicInteger();
        SoftwareTool softwareTool = new SoftwareTool((command, timeoutSeconds) -> {
            if (command.contains("list")) {
                boolean installed = listCalls.getAndIncrement() == 0;
                return installed
                        ? new SoftwareTool.CommandResult(0,
                                new SoftwareTool.CapturedOutput("Tencent QQ Tencent.QQ 9.7.23.29392", false),
                                new SoftwareTool.CapturedOutput("", false), false, 1)
                        : new SoftwareTool.CommandResult(0x8A150014,
                                new SoftwareTool.CapturedOutput("No installed package found matching input criteria.", false),
                                new SoftwareTool.CapturedOutput("", false), false, 1);
            }
            return new SoftwareTool.CommandResult(1605,
                    new SoftwareTool.CapturedOutput("Unknown product", false),
                    new SoftwareTool.CapturedOutput("", false), false, 1);
        }, true);
        ServiceTool serviceTool = new ServiceTool((command, environment, timeoutSeconds) -> {
            throw new AssertionError("Service tool should not run");
        }, true);
        OsProcessTool processTool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            throw new AssertionError("Process tool should not run");
        }, true);
        UninstallPreflightTool preflight = new UninstallPreflightTool(
                privilegeTool, softwareTool, serviceTool, processTool, true);
        UninstallWorkflowTool tool = new UninstallWorkflowTool(
                preflight, softwareTool, serviceTool, processTool, true);

        var result = tool.execute(Map.of("packageId", "Tencent.QQ"));

        assertTrue(result.success());
        assertEquals(true, result.result().get("uninstallVerified"));
        assertEquals(true, result.result().get("uninstallSucceeded"));
    }

    @Test
    void executeRemediatesBlockingServiceAndProcessesThenRetriesUninstall() {
        PrivilegeTool privilegeTool = new PrivilegeTool((command, timeoutSeconds) -> new PrivilegeTool.CommandResult(
                0,
                new PrivilegeTool.CapturedOutput("""
                        {"accountName":"NT AUTHORITY\\\\SYSTEM","userSid":"S-1-5-18","isLocalSystem":true,"isAdministratorToken":true,"isPrivileged":true,"os":"Microsoft Windows 11"}
                        """, false),
                new PrivilegeTool.CapturedOutput("", false),
                false,
                10), true);

        SoftwareTool softwareTool = new SoftwareTool((command, timeoutSeconds) -> {
            if (command.contains("list")) {
                return new SoftwareTool.CommandResult(
                        0,
                        new SoftwareTool.CapturedOutput("Tencent QQ Tencent.QQ 9.7.23.29392", false),
                        new SoftwareTool.CapturedOutput("", false),
                        false,
                        11);
            }
            if (command.contains("uninstall")) {
                return new SoftwareTool.CommandResult(
                        0,
                        new SoftwareTool.CapturedOutput("Uninstalled", false),
                        new SoftwareTool.CapturedOutput("", false),
                        false,
                        12);
            }
            throw new AssertionError("Unexpected winget command: " + command);
        }, true);

        AtomicInteger serviceQueryCalls = new AtomicInteger();
        ServiceTool serviceTool = new ServiceTool((command, environment, timeoutSeconds) -> {
            String script = command.get(command.size() - 1);
            if (script.contains("Stop-Service")) {
                return new ServiceTool.CommandResult(
                        0,
                        new ServiceTool.CapturedOutput("""
                                {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Stopped","status":"OK","startMode":"Disabled","serviceType":"Win32OwnProcess","processId":0,"canStop":false}
                                """, false),
                        new ServiceTool.CapturedOutput("", false),
                        false,
                        13);
            }
            if (serviceQueryCalls.getAndIncrement() == 0) {
                return new ServiceTool.CommandResult(
                        0,
                        new ServiceTool.CapturedOutput("""
                                {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Running","status":"OK","startMode":"Auto","serviceType":"Win32OwnProcess","processId":1234,"canStop":false}
                                """, false),
                        new ServiceTool.CapturedOutput("", false),
                        false,
                        14);
            }
            return new ServiceTool.CommandResult(
                    0,
                    new ServiceTool.CapturedOutput("""
                            {"name":"QQPCRTP","displayName":"Tencent QQ Service","state":"Stopped","status":"OK","startMode":"Disabled","serviceType":"Win32OwnProcess","processId":0,"canStop":false}
                            """, false),
                    new ServiceTool.CapturedOutput("", false),
                    false,
                    15);
        }, true);

        AtomicInteger processQueryCalls = new AtomicInteger();
        OsProcessTool osProcessTool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            String script = command.get(command.size() - 1);
            if (script.contains("Stop-Process")) {
                return new OsProcessTool.CommandResult(
                        0,
                        new OsProcessTool.CapturedOutput("""
                                {"processName":"QQPCTray.exe","allMatching":false,"requestedProcessIds":[4321],"targetedProcessIds":[4321],"terminatedCount":1,"remainingCount":0,"remaining":[]}
                                """, false),
                        new OsProcessTool.CapturedOutput("", false),
                        false,
                        16);
            }
            int call = processQueryCalls.getAndIncrement();
            if (call < 2) {
                return new OsProcessTool.CommandResult(
                        0,
                        new OsProcessTool.CapturedOutput("""
                                {"processName":"QQPCTray.exe","count":1,"truncated":false,"processes":[{"processId":4321,"name":"QQPCTray.exe","parentProcessId":100,"sessionId":1}]}
                                """, false),
                        new OsProcessTool.CapturedOutput("", false),
                        false,
                        17);
            }
            return new OsProcessTool.CommandResult(
                    0,
                    new OsProcessTool.CapturedOutput("""
                            {"processName":"QQPCTray.exe","count":0,"truncated":false,"processes":[]}
                            """, false),
                    new OsProcessTool.CapturedOutput("", false),
                    false,
                    18);
        }, true);

        UninstallPreflightTool preflightTool = new UninstallPreflightTool(privilegeTool, softwareTool, serviceTool, osProcessTool, true);
        UninstallWorkflowTool tool = new UninstallWorkflowTool(preflightTool, softwareTool, serviceTool, osProcessTool, true);

        var result = tool.execute(Map.of(
                "packageId", "Tencent.QQ",
                "serviceName", "QQPCRTP",
                "processNames", List.of("QQPCTray.exe")));

        assertTrue(result.success());
        assertEquals(true, result.result().get("uninstallSucceeded"));
        assertEquals(false, result.result().get("readyForUninstallBefore"));
        assertEquals(true, result.result().get("readyForUninstallAfter"));
        assertEquals(true, result.result().get("uninstallAttempted"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remediations = (List<Map<String, Object>>) result.result().get("remediations");
        assertEquals(2, remediations.size());
        Map<String, Object> serviceStep = remediations.stream()
                .filter(step -> "QQPCRTP".equals(step.get("serviceName")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> processStep = remediations.stream()
                .filter(step -> "QQPCTray.exe".equals(step.get("processName")))
                .findFirst()
                .orElseThrow();

        assertTrue((Boolean) serviceStep.get("attempted"));
        assertTrue((Boolean) serviceStep.get("serviceStopped"));
        assertTrue((Boolean) processStep.get("attempted"));
        assertTrue((Boolean) processStep.get("terminated"));

        @SuppressWarnings("unchecked")
        Map<String, Object> uninstallResult = (Map<String, Object>) result.result().get("uninstallResult");
        assertTrue((Boolean) uninstallResult.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> preflightAfter = (Map<String, Object>) result.result().get("preflightAfterRemediation");
        @SuppressWarnings("unchecked")
        Map<String, Object> preflightAfterResult = (Map<String, Object>) preflightAfter.get("result");
        assertEquals(true, preflightAfterResult.get("readyForUninstall"));
    }

    @Test
    void executeCanDryRunWithoutRetryingTheUninstall() {
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
                new SoftwareTool.CapturedOutput("Tencent QQ Tencent.QQ 9.7.23.29392", false),
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

        UninstallPreflightTool preflightTool = new UninstallPreflightTool(privilegeTool, softwareTool, serviceTool, osProcessTool, true);
        UninstallWorkflowTool tool = new UninstallWorkflowTool(preflightTool, softwareTool, serviceTool, osProcessTool, true);

        var result = tool.execute(Map.of(
                "packageId", "Tencent.QQ",
                "serviceName", "QQPCRTP",
                "processNames", List.of("QQPCTray.exe"),
                "stopService", false,
                "terminateProcesses", false,
                "retryUninstall", false));

        assertTrue(result.success());
        assertEquals(false, result.result().get("uninstallAttempted"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remediations = (List<Map<String, Object>>) result.result().get("remediations");
        assertFalse(remediations.isEmpty());
    }

    @Test
    void executeRejectsUnsafeArgumentsBeforeRunningPreflightOrRemediation() {
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
        UninstallPreflightTool preflightTool = new UninstallPreflightTool(privilegeTool, softwareTool, serviceTool, osProcessTool, true);
        UninstallWorkflowTool tool = new UninstallWorkflowTool(preflightTool, softwareTool, serviceTool, osProcessTool, true);

        var processResult = tool.execute(Map.of("packageId", "Tencent.QQ", "processNames", List.of("C:\\Temp\\QQPCTray.exe")));
        var booleanResult = tool.execute(Map.of("packageId", "Tencent.QQ", "retryUninstall", "sometimes"));

        assertFalse(processResult.success());
        assertTrue(processResult.errorMessage().contains("processNames entries"));
        assertFalse(booleanResult.success());
        assertTrue(booleanResult.errorMessage().contains("retryUninstall must be a boolean"));
        assertEquals(0, calls.get());
    }
}
