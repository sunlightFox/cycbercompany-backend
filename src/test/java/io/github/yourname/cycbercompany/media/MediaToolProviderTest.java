package io.github.yourname.cycbercompany.media;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.ToolDiscoveryRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaToolProviderTest {

    @Test
    void advertisesLowLatencyMediaAndPlayerContractsToAgentRouter() {
        MediaToolProvider provider = new MediaToolProvider(
                new TvBoxConfigService(new ObjectMapper()), null, null);
        List<String> names = provider.discover(new ToolDiscoveryRequest(
                        "run-1", null, List.of(), ActorContext.local()))
                .stream().map(descriptor -> descriptor.logicalName()).toList();

        assertTrue(names.contains("media.search"));
        assertTrue(names.contains("media.progress.get"));
        assertTrue(names.contains("media.progress.save"));
        assertTrue(names.contains("player.command"));
    }
}
