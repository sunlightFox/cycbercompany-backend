package io.github.yourname.cycbercompany.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NodeServiceDisconnectTest {

    @Test
    void disconnectMarksTheNodeOfflineWhenNoSessionIsAvailable() {
        ActorContext actor = ActorContext.local();
        NodeConnectionRepository nodes = mock(NodeConnectionRepository.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        NodeConnectionEntity node = new NodeConnectionEntity(
                "node-1",
                actor.tenantId(),
                "Test node",
                "host",
                "Windows",
                "amd64",
                "1.0",
                "secret-hash",
                Instant.now());
        node.markOnline(Instant.now());
        when(nodes.findByIdAndTenantId(node.id(), actor.tenantId())).thenReturn(Optional.of(node));
        when(sessions.requestShutdown(node.id(), "server requested client shutdown")).thenReturn(false);
        when(nodes.save(node)).thenReturn(node);
        NodeService service = new NodeService(
                nodes,
                mock(NodeRegistrationTokenRepository.class),
                mock(NodeToolRepository.class),
                mock(NodeToolInvocationRepository.class),
                mock(NodeToolApprovalRepository.class),
                sessions,
                new ObjectMapper());

        NodeConnectionView view = service.disconnect(node.id(), actor);

        assertThat(view.status()).isEqualTo(NodeStatus.OFFLINE);
        verify(sessions).requestShutdown(node.id(), "server requested client shutdown");
        verify(nodes).save(node);
    }
}
