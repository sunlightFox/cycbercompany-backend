package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class NodeSessionRegistryMessageBudgetTest {

    @Test
    void rejectsOversizedToolInvokeBeforeWritingToTheSocket() throws Exception {
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        NodeSessionRegistry registry = new NodeSessionRegistry(new ObjectMapper().findAndRegisterModules());
        registry.register("node-1", session);

        assertThatThrownBy(() -> registry.invoke(
                        "node-1",
                        "fs.write",
                        Map.of("content", "x".repeat(NodeProtocolLimits.MAX_CONTROL_MESSAGE_BYTES)),
                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("size limit");

        verify(session, never()).sendMessage(any());
    }
}
