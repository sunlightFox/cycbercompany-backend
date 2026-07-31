package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShellToolTest {

    @Test
    void executesCommandInsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "echo agent-studio", "cwd", ".", "timeoutSeconds", 5));

        assertTrue(result.success());
        assertTrue(result.result().get("stdout").toString().contains("agent-studio"));
        assertTrue(result.result().get("cwd").toString().startsWith(workspace.toRealPath().toString()));
    }

    @Test
    void rejectsWorkingDirectoryOutsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        ShellTool tool = new ShellTool(workspace);

        var result = tool.run(Map.of("command", "echo should-not-run", "cwd", workspace.getParent().toString()));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("configured workspace"));
    }

    @Test
    void systemAccessAcceptsAWorkingDirectoryOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-node-shell");
        Path outside = Files.createTempDirectory("agent-studio-node-system-shell");
        ShellTool tool = new ShellTool(workspace, true);

        var result = tool.run(Map.of("command", "echo system-access", "cwd", outside.toString(), "timeoutSeconds", 5));

        assertTrue(result.success());
        assertTrue(result.result().get("cwd").toString().startsWith(outside.toRealPath().toString()));
    }
}
