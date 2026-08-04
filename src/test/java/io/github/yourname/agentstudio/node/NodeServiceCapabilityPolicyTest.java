package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.execution.ExecutionMode;
import io.github.yourname.agentstudio.execution.ExecutionSettingsService;
import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
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
    @Mock
    private ExecutionSettingsService executionSettings;

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

    @Test
    void grantsSystemToolsFullAccessWithoutApprovalOnlyWhenExplicitlyEnabled() {
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
        Instant now = Instant.now();
        NodeConnectionEntity node = new NodeConnectionEntity(
                "node-1", "tenant-a", "desktop", "host", "Windows", "amd64", "test", "secret", now);
        node.updateCapabilitySnapshot(null, Map.of(), Set.of("system-access.v1"), now);
        NodeToolEntity delete = new NodeToolEntity(
                "tenant-a", "node-1", "system.fs.delete", "Delete a file", RiskLevel.HIGH, true, true, "{}", now);
        NodeToolEntity shell = new NodeToolEntity(
                "tenant-a", "node-1", "system.shell.run", "Run a command", RiskLevel.HIGH, true, true, "{}", now);
        NodeToolEntity workspaceRead = new NodeToolEntity(
                "tenant-a", "node-1", "fs.read", "Read a workspace file", RiskLevel.LOW, true, false, "{}", now);
        when(nodes.findByIdAndTenantId("node-1", "tenant-a")).thenReturn(Optional.of(node));
        when(tools.findByTenantIdAndNodeIdOrderByNameAsc("tenant-a", "node-1"))
                .thenReturn(List.of(delete, shell, workspaceRead));

        service.setSystemAccess("node-1", true, new ActorContext("tenant-a", "alice", Set.of(), Set.of()));

        assertThat(delete.enabled()).isTrue();
        assertThat(delete.requiresApproval()).isFalse();
        assertThat(shell.enabled()).isTrue();
        assertThat(shell.requiresApproval()).isFalse();
        assertThat(workspaceRead.enabled()).isTrue();
        assertThat(workspaceRead.requiresApproval()).isFalse();

        service.setSystemAccess("node-1", false, new ActorContext("tenant-a", "alice", Set.of(), Set.of()));

        assertThat(delete.enabled()).isFalse();
        assertThat(delete.requiresApproval()).isTrue();
        assertThat(shell.enabled()).isFalse();
        assertThat(shell.requiresApproval()).isTrue();
        assertThat(workspaceRead.enabled()).isTrue();
        assertThat(workspaceRead.requiresApproval()).isFalse();
        verify(tools, times(2)).save(delete);
        verify(tools, times(2)).save(shell);
    }

    @Test
    void resolvesTheOnlyConnectedNodeWithEnabledSystemTools() {
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
        NodeConnectionEntity node = new NodeConnectionEntity(
                "node-system", "tenant-a", "my-pc", "host", "Windows", "amd64", "test", "secret",
                NodeKind.MANAGED_LOCAL, Instant.now());
        node.markOnline(Instant.now());
        NodeToolEntity tool = new NodeToolEntity(
                "tenant-a", "node-system", "system.fs.list", "List files", RiskLevel.HIGH, true, true, "{}", Instant.now());
        when(nodes.findByTenantIdOrderByCreatedAtDesc("tenant-a")).thenReturn(List.of(node));
        when(sessions.isConnected("node-system")).thenReturn(true);
        when(tools.findByTenantIdAndNodeIdOrderByNameAsc("tenant-a", "node-system")).thenReturn(List.of(tool));

        String nodeId = service.resolveComputerControlNodeId(
                new ActorContext("tenant-a", "alice", Set.of(), Set.of()));

        assertThat(nodeId).isEqualTo("node-system");
    }

    @Test
    void rejectsManagedLocalTargetWhenNodesOnlyModeIsSelected() {
        ActorContext actor = new ActorContext("tenant-a", "alice", Set.of(), Set.of());
        NodeService service = new NodeService(
                nodes,
                tokens,
                tools,
                invocations,
                approvals,
                sessions,
                new ObjectMapper(),
                new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults()),
                executionSettings);
        NodeConnectionEntity local = new NodeConnectionEntity(
                "local-1", "tenant-a", "This computer", "host", "Windows", "amd64", "test", "secret",
                NodeKind.MANAGED_LOCAL, Instant.now());
        when(nodes.findByIdAndTenantId("local-1", "tenant-a")).thenReturn(Optional.of(local));
        when(executionSettings.mode(actor)).thenReturn(ExecutionMode.NODES_ONLY);

        assertThatThrownBy(() -> service.validateExecutionTarget("local-1", actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered nodes only");
    }

    @Test
    void routesAutoWorkOnlyToAMatchingTrustedSandbox() {
        ActorContext actor = new ActorContext("tenant-a", "alice", Set.of(), Set.of());
        NodeService service = nodesOnlyService();
        Instant now = Instant.now();
        NodeConnectionEntity sandbox = new NodeConnectionEntity(
                "sandbox-linux", "tenant-a", "Linux build sandbox", "sandbox-host", "Linux", "amd64", "test", "secret",
                NodeKind.SANDBOX, now);
        sandbox.markOnline(now);
        sandbox.updateSchedulingMetadata(null, Set.of("linux", "java-21"), now);
        NodeConnectionEntity personalComputer = new NodeConnectionEntity(
                "personal-pc", "tenant-a", "Alice laptop", "laptop", "Windows", "amd64", "test", "secret", now);
        personalComputer.markOnline(now);
        personalComputer.updateSchedulingMetadata(null, Set.of("linux", "java-21"), now);
        NodeConnectionEntity incompleteSandbox = new NodeConnectionEntity(
                "sandbox-limited", "tenant-a", "Limited sandbox", "limited-host", "Linux", "amd64", "test", "secret",
                NodeKind.SANDBOX, now);
        incompleteSandbox.markOnline(now);
        incompleteSandbox.updateSchedulingMetadata(null, Set.of("linux", "java-21"), now);
        NodeToolEntity sourceRead = new NodeToolEntity(
                "tenant-a", "sandbox-linux", "fs.read", "Read a source file", RiskLevel.LOW, true, false, "{}", now);
        NodeToolEntity sourceList = new NodeToolEntity(
                "tenant-a", "sandbox-limited", "fs.list", "List source files", RiskLevel.LOW, true, false, "{}", now);

        when(nodes.findByTenantIdOrderByCreatedAtDesc("tenant-a"))
                .thenReturn(List.of(personalComputer, incompleteSandbox, sandbox));
        when(sessions.isConnected("sandbox-linux")).thenReturn(true);
        when(sessions.isConnected("sandbox-limited")).thenReturn(true);
        when(tools.findByTenantIdAndNodeIdOrderByNameAsc("tenant-a", "sandbox-linux")).thenReturn(List.of(sourceRead));
        when(tools.findByTenantIdAndNodeIdOrderByNameAsc("tenant-a", "sandbox-limited")).thenReturn(List.of(sourceList));

        String selected = service.resolveSandboxNodeId(List.of("LINUX", "java-21"), List.of("fs.read"), actor);

        assertThat(selected).isEqualTo("sandbox-linux");
    }

    @Test
    void doesNotTreatAnOrdinaryRegisteredComputerAsAnAutoRoutableSandbox() {
        ActorContext actor = new ActorContext("tenant-a", "alice", Set.of(), Set.of());
        NodeService service = nodesOnlyService();
        Instant now = Instant.now();
        NodeConnectionEntity personalComputer = new NodeConnectionEntity(
                "personal-pc", "tenant-a", "Alice laptop", "laptop", "Windows", "amd64", "test", "secret", now);
        personalComputer.markOnline(now);
        personalComputer.updateSchedulingMetadata(null, Set.of("linux"), now);

        when(nodes.findByTenantIdOrderByCreatedAtDesc("tenant-a")).thenReturn(List.of(personalComputer));

        assertThatThrownBy(() -> service.resolveSandboxNodeId(List.of("linux"), List.of(), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No connected trusted sandbox");
    }

    @Test
    void roundRobinsBetweenEquivalentTrustedSandboxCandidates() {
        ActorContext actor = new ActorContext("tenant-a", "alice", Set.of(), Set.of());
        NodeService service = nodesOnlyService();
        Instant now = Instant.now();
        NodeConnectionEntity first = sandbox("sandbox-a", now, Set.of("linux"));
        NodeConnectionEntity second = sandbox("sandbox-b", now, Set.of("linux"));
        NodeToolEntity firstRead = new NodeToolEntity(
                "tenant-a", "sandbox-a", "fs.read", "Read", RiskLevel.LOW, true, false, "{}", now);
        NodeToolEntity secondRead = new NodeToolEntity(
                "tenant-a", "sandbox-b", "fs.read", "Read", RiskLevel.LOW, true, false, "{}", now);
        when(nodes.findByTenantIdOrderByCreatedAtDesc("tenant-a")).thenReturn(List.of(second, first));
        when(sessions.isConnected("sandbox-a")).thenReturn(true);
        when(sessions.isConnected("sandbox-b")).thenReturn(true);
        when(tools.findByTenantIdAndNodeIdOrderByNameAsc("tenant-a", "sandbox-a")).thenReturn(List.of(firstRead));
        when(tools.findByTenantIdAndNodeIdOrderByNameAsc("tenant-a", "sandbox-b")).thenReturn(List.of(secondRead));

        String firstSelection = service.resolveSandboxNodeId(List.of("linux"), List.of("fs.read"), actor);
        String secondSelection = service.resolveSandboxNodeId(List.of("linux"), List.of("fs.read"), actor);
        String thirdSelection = service.resolveSandboxNodeId(List.of("linux"), List.of("fs.read"), actor);

        assertThat(List.of(firstSelection, secondSelection, thirdSelection))
                .containsExactly("sandbox-a", "sandbox-b", "sandbox-a");
    }

    @Test
    void rejectsTurningTheManagedLocalComputerIntoAnAutoRoutableSandbox() {
        ActorContext actor = new ActorContext("tenant-a", "alice", Set.of(), Set.of());
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
        NodeConnectionEntity local = new NodeConnectionEntity(
                "local-1", "tenant-a", "This computer", "host", "Windows", "amd64", "test", "secret",
                NodeKind.MANAGED_LOCAL, Instant.now());
        when(nodes.findByIdAndTenantId("local-1", "tenant-a")).thenReturn(Optional.of(local));

        assertThatThrownBy(() -> service.update(
                "local-1", new UpdateNodeCommand(null, null, NodeKind.SANDBOX, Set.of("windows")), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be converted into a sandbox");
    }

    @Test
    void administratorCanMarkARegisteredNodeAsSandboxAndNormalizeItsLabels() {
        ActorContext actor = new ActorContext("tenant-a", "alice", Set.of(), Set.of());
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
        NodeConnectionEntity node = new NodeConnectionEntity(
                "registered-1", "tenant-a", "build host", "host", "Linux", "amd64", "test", "secret", Instant.now());
        when(nodes.findByIdAndTenantId("registered-1", "tenant-a")).thenReturn(Optional.of(node));
        when(nodes.save(any(NodeConnectionEntity.class))).thenAnswer(call -> call.getArgument(0));

        NodeConnectionView updated = service.update(
                "registered-1",
                new UpdateNodeCommand(null, null, NodeKind.SANDBOX, Set.of("LINUX", "java-21")),
                actor);

        assertThat(updated.kind()).isEqualTo(NodeKind.SANDBOX);
        assertThat(updated.labels()).containsExactlyInAnyOrder("linux", "java-21");
    }

    private NodeService nodesOnlyService() {
        NodeService service = new NodeService(
                nodes,
                tokens,
                tools,
                invocations,
                approvals,
                sessions,
                new ObjectMapper(),
                new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults()),
                executionSettings);
        when(executionSettings.mode(org.mockito.ArgumentMatchers.any(ActorContext.class)))
                .thenReturn(ExecutionMode.NODES_ONLY);
        return service;
    }

    private static NodeConnectionEntity sandbox(String id, Instant now, Set<String> labels) {
        NodeConnectionEntity node = new NodeConnectionEntity(
                id, "tenant-a", id, "host", "Linux", "amd64", "test", "secret", NodeKind.SANDBOX, now);
        node.markOnline(now);
        node.updateSchedulingMetadata(null, labels, now);
        return node;
    }

    @Test
    void exposesOnlyBoundedJournalReconciliationMetadataAfterReconnect() {
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "inv-1", "tenant-a", "run-1", "call-1", "node-1", "fs.write", "{\"path\":\"secret.txt\"}", now);
        invocation.dispatch(3, now.plusSeconds(30), "sha256:args", "idem-1", "policy-1", now);
        when(invocations.findByNodeIdAndStatusInOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("node-1"),
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any(Pageable.class))).thenReturn(List.of(invocation));
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());

        List<NodeService.NodeInvocationReconciliation> requests = service.reconciliationRequests("node-1", 999);

        assertThat(requests).containsExactly(new NodeService.NodeInvocationReconciliation(
                "inv-1", "fs.write", "sha256:args", 3));
    }

    @Test
    void reconcilesLateTerminalResultOnlyWhenThePersistedDispatchMetadataMatches() {
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "inv-1", "tenant-a", "run-1", "call-1", "node-1", "fs.write", "{}", now);
        invocation.dispatch(2, now.plusSeconds(30), "sha256:args", "idem-1", "policy-1", now);
        invocation.unknown("network interrupted", now);
        when(invocations.findByIdAndNodeId("inv-1", "node-1")).thenReturn(Optional.of(invocation));
        when(invocations.save(any(NodeToolInvocationEntity.class))).thenAnswer(call -> call.getArgument(0));
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());

        boolean rejected = service.reconcileInvocationResult(
                new NodeToolCallResult("inv-1", "node-1", "fs.write", "SUCCEEDED", Map.of(), null),
                "fs.write", "sha256:other", 2);
        boolean reconciled = service.reconcileInvocationResult(
                new NodeToolCallResult("inv-1", "node-1", "fs.write", "SUCCEEDED", Map.of("written", true), null),
                "fs.write", "sha256:args", 2);

        assertThat(reconciled).isTrue();
        assertThat(invocation.status()).isEqualTo(NodeToolInvocationStatus.SUCCEEDED);
        assertThat(rejected).isFalse();
    }

    @Test
    void rejectsIntermediateStatusWhenDispatchMetadataDoesNotMatch() {
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "inv-progress", "tenant-a", "run-1", "call-1", "node-1", "fs.write", "{}", now);
        invocation.dispatch(2, now.plusSeconds(30), "sha256:args", "idem-1", "policy-1", now);
        when(invocations.findByIdAndNodeId("inv-progress", "node-1")).thenReturn(Optional.of(invocation));
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());

        service.startInvocation("node-1", "inv-progress", "fs.write", "sha256:wrong", 2);

        assertThat(invocation.status()).isEqualTo(NodeToolInvocationStatus.DISPATCHED);
        verify(invocations, never()).save(any(NodeToolInvocationEntity.class));
    }

    @Test
    void acceptsIntermediateStatusWhenDispatchMetadataMatches() {
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "inv-progress-ok", "tenant-a", "run-1", "call-1", "node-1", "fs.write", "{}", now);
        invocation.dispatch(2, now.plusSeconds(30), "sha256:args", "idem-1", "policy-1", now);
        when(invocations.findByIdAndNodeId("inv-progress-ok", "node-1")).thenReturn(Optional.of(invocation));
        when(invocations.save(any(NodeToolInvocationEntity.class))).thenAnswer(call -> call.getArgument(0));
        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());

        service.acceptInvocation("node-1", "inv-progress-ok", "fs.write", "sha256:args", 2);

        assertThat(invocation.status()).isEqualTo(NodeToolInvocationStatus.ACCEPTED);
        verify(invocations).save(invocation);
    }
}
