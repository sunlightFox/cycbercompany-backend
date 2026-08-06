package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolDescriptorTest {

    @Test
    void treatsTopLevelNullMetadataValuesAsOmitted() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("default", null);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("nodeId", "node-a");
        attributes.put("toolVersion", null);

        ToolDescriptor descriptor = new ToolDescriptor(
                "node:node-a:system.shell.run",
                "system.shell.run",
                "node",
                "system.shell.run",
                "Run shell command",
                RiskLevel.LOW,
                false,
                schema,
                attributes);

        assertThat(descriptor.inputSchema())
                .containsEntry("type", "object")
                .doesNotContainKey("default");
        assertThat(descriptor.attributes())
                .containsExactly(Map.entry("nodeId", "node-a"));
    }
}
