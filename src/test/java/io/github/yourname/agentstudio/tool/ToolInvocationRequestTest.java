package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolInvocationRequestTest {

    @Test
    void treatsTopLevelNullArgumentsAsOmitted() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", "pwd");
        arguments.put("cwd", null);
        arguments.put(null, "ignored");
        ActorContext actor = new ActorContext("tenant", "user", Set.of(), Set.of());

        ToolInvocationRequest request = new ToolInvocationRequest(
                "run-1",
                "call-1",
                binding(),
                arguments,
                null,
                CodingWorkspaceScope.from(null),
                actor);

        assertThat(request.arguments()).containsExactly(Map.entry("command", "pwd"));
        assertThat(request.arguments().keySet()).doesNotContainNull();
    }

    private static ResolvedToolBinding binding() {
        return new ResolvedToolBinding(
                "node:node-a:system.shell.run",
                "tool_system_shell_run_123456",
                "system.shell.run",
                "node",
                "system.shell.run",
                "Run a shell command",
                RiskLevel.LOW,
                false,
                Map.of("type", "object"),
                Map.of("nodeId", "node-a"));
    }
}
