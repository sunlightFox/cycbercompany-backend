package io.github.yourname.agentstudio.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.ToolDiscoveryRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InProcessLocalToolProviderTest {

    @Test
    void exposesSystemCapabilitiesOnlyForTheInProcessTarget() {
        InProcessLocalToolProvider provider = new InProcessLocalToolProvider();
        ActorContext actor = new ActorContext("tenant", "user", Set.of(), Set.of());

        assertThat(provider.discover(new ToolDiscoveryRequest(
                "run", InProcessLocalToolProvider.TARGET_ID, List.of(), List.of(), actor)))
                .extracting(tool -> tool.logicalName())
                .contains("system.fs.list", "system.shell.run", "process.start");
        assertThat(provider.discover(new ToolDiscoveryRequest("run", "node-1", List.of(), List.of(), actor)))
                .isEmpty();
    }
}
