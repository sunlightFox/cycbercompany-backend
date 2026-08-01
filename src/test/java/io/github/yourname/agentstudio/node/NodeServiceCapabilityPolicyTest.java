package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证节点报告能力时，权限决策只来自服务端目录。 */
@ExtendWith(MockitoExtension.class)
class NodeServiceCapabilityPolicyTest {

    @Mock
    private NodeConnectionRepository nodes;
    @Mock
    private NodeRegistrationTokenRepository tokens;
    @Mock
    private NodeToolRepository tools;
    @Mock
    private NodeToolInvocationRepository invocations;
    @Mock
    private NodeToolApprovalRepository approvals;
    @Mock
    private NodeSessionRegistry sessions;

    @Test
    void assignsWallpaperRiskAndApprovalOnTheServer() {
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
        NodeConnectionEntity node = new NodeConnectionEntity(
                "node-1", "tenant-a", "desktop", "host", "Windows", "amd64", "test", "secret", Instant.now());
        when(nodes.findById("node-1")).thenReturn(Optional.of(node));
        when(tools.findByTenantIdAndNodeIdAndName("tenant-a", "node-1", "system.desktop.set_wallpaper"))
                .thenReturn(Optional.empty());
        when(tools.save(any(NodeToolEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tools.findByTenantIdAndNodeIdOrderByNameAsc("tenant-a", "node-1")).thenReturn(List.of());

        service.saveCapabilities(
                "node-1",
                "sha256:" + "a".repeat(64),
                Map.of("java", "21.0.4"),
                java.util.Set.of("workspace.scope.v1"),
                List.of(new NodeCapabilityPayload(
                        "system.desktop.set_wallpaper",
                        "Set desktop wallpaper.",
                        "2",
                        Map.of("type", "object"))));

        ArgumentCaptor<NodeToolEntity> saved = ArgumentCaptor.forClass(NodeToolEntity.class);
        verify(tools).save(saved.capture());
        assertThat(saved.getValue().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(saved.getValue().enabled()).isTrue();
        assertThat(saved.getValue().requiresApproval()).isTrue();
        assertThat(saved.getValue().capabilityVersion()).isEqualTo("2");
        assertThat(node.capabilityRevision()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(node.runtimeVersions()).containsEntry("java", "21.0.4");
        assertThat(node.features()).contains("workspace.scope.v1");
    }
}
