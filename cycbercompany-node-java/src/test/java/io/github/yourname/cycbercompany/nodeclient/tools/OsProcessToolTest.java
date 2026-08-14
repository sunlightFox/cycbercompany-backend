package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OsProcessToolTest {

    @Test
    void queryUsesExactImageNameThroughEnvironment() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        AtomicReference<Map<String, String>> observedEnvironment = new AtomicReference<>();
        OsProcessTool tool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            observedCommand.set(command);
            observedEnvironment.set(environment);
            return new OsProcessTool.CommandResult(
                    0,
                    new OsProcessTool.CapturedOutput("""
                            {"processName":"QQPCTray.exe","count":1,"truncated":false,"processes":[{"processId":1234,"name":"QQPCTray.exe","parentProcessId":100,"sessionId":1}]}
                            """, false),
                    new OsProcessTool.CapturedOutput("", false),
                    false,
                    14);
        }, true);

        var result = tool.query(Map.of("processName", "QQPCTray.exe"));

        assertTrue(result.success());
        assertEquals(List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command"), observedCommand.get().subList(0, 6));
        assertEquals("QQPCTray.exe", observedEnvironment.get().get("CYCBERCOMPANY_PROCESS_NAME"));
        assertEquals("QQPCTray.exe", ((Map<?, ?>) result.result().get("snapshot")).get("processName"));
    }

    @Test
    void terminateRequiresIdsOrExplicitAllMatching() {
        AtomicBoolean called = new AtomicBoolean(false);
        OsProcessTool tool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            called.set(true);
            return new OsProcessTool.CommandResult(
                    0,
                    new OsProcessTool.CapturedOutput("", false),
                    new OsProcessTool.CapturedOutput("", false),
                    false,
                    1);
        }, true);

        var result = tool.terminate(Map.of("processName", "QQPCTray.exe"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Supply processIds"));
        assertFalse(called.get());
    }

    @Test
    void terminateCanTargetExplicitProcessIds() {
        AtomicReference<Map<String, String>> observedEnvironment = new AtomicReference<>();
        OsProcessTool tool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            observedEnvironment.set(environment);
            return new OsProcessTool.CommandResult(
                    0,
                    new OsProcessTool.CapturedOutput("""
                            {"processName":"QQPCTray.exe","allMatching":false,"requestedProcessIds":[1234],"targetedProcessIds":[1234],"terminatedCount":1,"remainingCount":0,"remaining":[]}
                            """, false),
                    new OsProcessTool.CapturedOutput("", false),
                    false,
                    28);
        }, true);

        var result = tool.terminate(Map.of("processName", "QQPCTray.exe", "processIds", List.of(1234)));

        assertTrue(result.success());
        assertEquals("QQPCTray.exe", observedEnvironment.get().get("CYCBERCOMPANY_PROCESS_NAME"));
        assertEquals("1234", observedEnvironment.get().get("CYCBERCOMPANY_PROCESS_IDS"));
        assertEquals("false", observedEnvironment.get().get("CYCBERCOMPANY_PROCESS_ALL"));
        assertEquals(1, ((Map<?, ?>) result.result().get("snapshot")).get("terminatedCount"));
    }

    @Test
    void terminateCanExplicitlyTargetAllExactMatches() {
        AtomicReference<Map<String, String>> observedEnvironment = new AtomicReference<>();
        OsProcessTool tool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            observedEnvironment.set(environment);
            return new OsProcessTool.CommandResult(
                    0,
                    new OsProcessTool.CapturedOutput("""
                            {"processName":"QQPCRTP.exe","allMatching":true,"requestedProcessIds":[],"targetedProcessIds":[44,45],"terminatedCount":2,"remainingCount":0,"remaining":[]}
                            """, false),
                    new OsProcessTool.CapturedOutput("", false),
                    false,
                    30);
        }, true);

        var result = tool.terminate(Map.of("processName", "QQPCRTP.exe", "allMatching", true));

        assertTrue(result.success());
        assertEquals("", observedEnvironment.get().get("CYCBERCOMPANY_PROCESS_IDS"));
        assertEquals("true", observedEnvironment.get().get("CYCBERCOMPANY_PROCESS_ALL"));
        assertEquals(2, ((Map<?, ?>) result.result().get("snapshot")).get("terminatedCount"));
    }

    @Test
    void rejectsUnsafeOrNonExecutableProcessNamesBeforeStartingPowerShell() {
        AtomicBoolean called = new AtomicBoolean(false);
        OsProcessTool tool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            called.set(true);
            return new OsProcessTool.CommandResult(
                    0,
                    new OsProcessTool.CapturedOutput("", false),
                    new OsProcessTool.CapturedOutput("", false),
                    false,
                    1);
        }, true);

        var result = tool.query(Map.of("processName", "QQPCTray && calc"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("exact Windows image name"));
        assertFalse(called.get());
    }

    @Test
    void nonWindowsNodesRejectOsProcessOperations() {
        AtomicBoolean called = new AtomicBoolean(false);
        OsProcessTool tool = new OsProcessTool((command, environment, timeoutSeconds) -> {
            called.set(true);
            return new OsProcessTool.CommandResult(
                    0,
                    new OsProcessTool.CapturedOutput("", false),
                    new OsProcessTool.CapturedOutput("", false),
                    false,
                    1);
        }, false);

        var result = tool.query(Map.of("processName", "QQPCTray.exe"));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Windows processes only"));
        assertFalse(called.get());
    }
}
