package io.github.yourname.cycbercompany.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.ToolDiscoveryRequest;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InProcessLocalToolProviderTest {

    @Test
    void exposesSystemCapabilitiesOnlyForTheInProcessTarget() {
        Path workspace;
        try {
            workspace = Files.createTempDirectory("in-process-local-provider");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        InProcessLocalToolProvider provider = new InProcessLocalToolProvider(
                new io.github.yourname.cycbercompany.config.LocalExecutorProperties(true, workspace));
        ActorContext actor = new ActorContext("tenant", "user", Set.of(), Set.of());

        assertThat(provider.discover(new ToolDiscoveryRequest(
                "run", InProcessLocalToolProvider.TARGET_ID, List.of(), List.of(), actor)))
                .extracting(tool -> tool.logicalName())
                .contains("system.fs.list", "system.shell.run", "process.start");
        assertThat(provider.discover(new ToolDiscoveryRequest("run", "node-1", List.of(), List.of(), actor)))
                .isEmpty();
        assertThat(provider.discover(new ToolDiscoveryRequest(
                "run", InProcessLocalToolProvider.TARGET_ID, List.of(), List.of(), actor)))
                .allSatisfy(tool -> {
                    assertThat(tool.requiresApproval()).isFalse();
                });
        provider.close();
    }
}
