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

class NodeServiceCredentialRotationTest {

    @Test
    void rotatesOnlyTheStoredHashAndDisconnectsTheOldSession() {
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
                "old-hash",
                Instant.now());
        when(nodes.findByIdAndTenantId(node.id(), actor.tenantId())).thenReturn(Optional.of(node));
        when(nodes.saveAndFlush(node)).thenReturn(node);
        NodeService service = new NodeService(
                nodes,
                mock(NodeRegistrationTokenRepository.class),
                mock(NodeToolRepository.class),
                mock(NodeToolInvocationRepository.class),
                mock(NodeToolApprovalRepository.class),
                sessions,
                new ObjectMapper());

        RotateNodeSecretResult result = service.rotateSecret(node.id(), actor);

        assertThat(result.nodeSecret()).startsWith("ns_").hasSizeGreaterThan(50);
        assertThat(result.websocketUrl()).isEqualTo("/api/v1/node-channel");
        assertThat(node.secretHash()).isNotEqualTo("old-hash").doesNotContain(result.nodeSecret());
        assertThat(node.status()).isEqualTo(NodeStatus.OFFLINE);
        verify(nodes).saveAndFlush(node);
        verify(sessions).disconnect(node.id(), "node credential rotated");
    }
}
