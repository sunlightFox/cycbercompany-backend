package io.github.yourname.agentstudio.nodeclient.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void advertisesBrowserWaitingWithItsRequiredSelector() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "browser.wait".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertTrue(capability.enabled());
        assertEquals("LOW", capability.riskLevel());
        assertEquals(java.util.List.of("selector"), capability.inputSchema().get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) capability.inputSchema().get("properties");
        assertTrue(properties.containsKey("timeoutMs"));
        registry.close();
    }
}
