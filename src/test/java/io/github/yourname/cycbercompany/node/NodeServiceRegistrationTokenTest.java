package io.github.yourname.cycbercompany.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NodeServiceRegistrationTokenTest {

    @Test
    void openRegistrationCreatesANodeWithANewSecret() {
        NodeConnectionRepository nodes = mock(NodeConnectionRepository.class);
        when(nodes.save(any(NodeConnectionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NodeService service = new NodeService(
                nodes, mock(NodeRegistrationTokenRepository.class), mock(NodeToolRepository.class),
                mock(NodeToolInvocationRepository.class), mock(NodeToolApprovalRepository.class),
                mock(NodeSessionRegistry.class), new ObjectMapper());

        RegisterNodeResult result = service.register(new RegisterNodeCommand("first", null, null, null, null));

        assertThat(result.nodeId()).startsWith("node_");
        assertThat(result.nodeSecret()).startsWith("ns_");
    }
}
