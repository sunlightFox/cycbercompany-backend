package io.github.yourname.agentstudio.node;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NodeToolInvocationEntityTest {

    @Test
    void successClearsAnEarlierApprovalPlaceholderError() {
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "invocation-1",
                "tenant-a",
                "run-1",
                "call-1",
                "node-1",
                "system.shell.run",
                "{\"command\":\"echo ok\"}",
                Instant.now());
        invocation.fail(NodeToolInvocationStatus.APPROVAL_REQUIRED, "approval required", Instant.now());

        invocation.succeed("{\"stdout\":\"ok\"}", "digest", Instant.now());

        assertNull(invocation.errorMessage());
    }
}
