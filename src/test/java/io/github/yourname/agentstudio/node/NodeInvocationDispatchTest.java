package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeInvocationDispatchTest {

    @Test
    void treatsTopLevelNullArgumentsAsOmitted() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", "whoami");
        arguments.put("cwd", null);
        arguments.put(null, "ignored");

        NodeInvocationDispatch dispatch = new NodeInvocationDispatch(
                "nodeinv-1",
                "run-1",
                "call-1",
                "system.shell.run",
                arguments,
                null,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                "rev-1",
                "sha256:test",
                1,
                "idem-1",
                "trace-1");

        assertThat(dispatch.arguments()).containsExactly(Map.entry("command", "whoami"));
        assertThat(dispatch.arguments().keySet()).doesNotContainNull();
    }
}
