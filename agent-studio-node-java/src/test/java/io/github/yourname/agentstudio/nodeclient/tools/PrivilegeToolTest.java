package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PrivilegeToolTest {

    @Test
    void queryReturnsStructuredPrivilegeFacts() {
        AtomicReference<List<String>> observedCommand = new AtomicReference<>();
        PrivilegeTool tool = new PrivilegeTool((command, timeoutSeconds) -> {
            observedCommand.set(command);
            return new PrivilegeTool.CommandResult(
                    0,
                    new PrivilegeTool.CapturedOutput("""
                    {"accountName":"NT AUTHORITY\\\\SYSTEM","userSid":"S-1-5-18","isLocalSystem":true,"isAdministratorToken":true,"isPrivileged":true,"os":"Microsoft Windows 11"}
                            """, false),
                    new PrivilegeTool.CapturedOutput("", false),
                    false,
                    11);
        }, true);

        var result = tool.query();

        assertTrue(result.success());
        assertEquals(List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command"), observedCommand.get().subList(0, 6));
        assertEquals("NT AUTHORITY\\SYSTEM", ((Map<?, ?>) result.result().get("privilege")).get("accountName"));
    }

    @Test
    void nonWindowsNodesFailGracefully() {
        AtomicBoolean called = new AtomicBoolean(false);
        PrivilegeTool tool = new PrivilegeTool((command, timeoutSeconds) -> {
            called.set(true);
            return new PrivilegeTool.CommandResult(
                    0,
                    new PrivilegeTool.CapturedOutput("", false),
                    new PrivilegeTool.CapturedOutput("", false),
                    false,
                    1);
        }, false);

        var result = tool.query();

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Windows only"));
        assertFalse(called.get());
    }
}
