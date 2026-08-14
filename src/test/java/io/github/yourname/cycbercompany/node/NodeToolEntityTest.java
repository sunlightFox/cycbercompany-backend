package io.github.yourname.cycbercompany.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yourname.cycbercompany.tool.RiskLevel;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NodeToolEntityTest {

    @Test
    void capabilityRefreshPreservesAdministratorPolicy() {
        Instant now = Instant.now();
        NodeToolEntity tool = new NodeToolEntity(
                "tenant",
                "node",
                "shell.run",
                "old description",
                RiskLevel.HIGH,
                true,
                false,
                "{}",
                now);

        tool.refreshCapability("new description", RiskLevel.HIGH, "{\"type\":\"object\"}", now.plusSeconds(1));

        assertTrue(tool.enabled());
        assertFalse(tool.requiresApproval());
    }
}
