package io.github.yourname.cycbercompany.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.config.AppProperties;
import io.github.yourname.cycbercompany.node.CallNodeToolCommand;
import io.github.yourname.cycbercompany.node.NodeConnectionView;
import io.github.yourname.cycbercompany.node.NodeDetailView;
import io.github.yourname.cycbercompany.node.NodeKind;
import io.github.yourname.cycbercompany.node.NodeService;
import io.github.yourname.cycbercompany.node.NodeStatus;
import io.github.yourname.cycbercompany.node.NodeToolCallResult;
import io.github.yourname.cycbercompany.node.NodeToolView;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.CodingWorkspaceScope;
import io.github.yourname.cycbercompany.tool.ResolvedToolBinding;
import io.github.yourname.cycbercompany.tool.RiskLevel;
import io.github.yourname.cycbercompany.tool.ToolDescriptor;
import io.github.yourname.cycbercompany.tool.ToolDiscoveryRequest;
import io.github.yourname.cycbercompany.tool.ToolInvocationRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class SkillToolProviderTest {

    @TempDir
    Path temporaryDirectory;

    private SkillCatalog catalog;
    private ObjectMapper objectMapper;
    private NodeService nodes;
    private SkillToolProvider provider;
    private ActorContext actor;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Path install = temporaryDirectory.resolve("data/skills");
        catalog = new SkillCatalog(
                new AppProperties(
                        temporaryDirectory.resolve("data"), null, null, null,
                        new AppProperties.SkillStore(install, 15 * 1024 * 1024, 300, 1024 * 1024), null, null),
                objectMapper);
        catalog.ensureInstallDirectoryExists();
        nodes = mock(NodeService.class);
        provider = new SkillToolProvider(catalog, new SkillAnalyzer(catalog), nodes);
        actor = ActorContext.local();
    }

    @Test
    void bindsResourcePathsToThePinnedReleaseAndRejectsOtherFiles() throws Exception {
        SkillRunBinding binding = installAndResolve(
                "resource-skill", "# Resource Skill", Map.of("references/guide.md", "Only this reference is exposed."));
        ToolDiscoveryRequest discovery = new ToolDiscoveryRequest(
                "run-1", null, List.of(), List.of(), List.of(binding), actor);

        ToolDescriptor descriptor = provider.discover(discovery).getFirst();
        ResolvedToolBinding resolved = resolved(descriptor);
        var success = provider.invoke(new ToolInvocationRequest(
                "run-1", "call-1", resolved, Map.of("path", "references/guide.md"), 30,
                CodingWorkspaceScope.from(null), actor));
        var rejected = provider.invoke(new ToolInvocationRequest(
                "run-1", "call-2", resolved, Map.of("path", "scripts/hidden.py"), 30,
                CodingWorkspaceScope.from(null), actor));

        assertThat(descriptor.attributes())
                .containsEntry("skillId", "resource-skill")
                .containsEntry("releaseDigest", binding.digest());
        assertThat(descriptor.description())
                .contains("immutable release", "Choose 'path' from the schema enum", "untrusted reference data");
        assertThat(descriptor.inputSchema()).containsEntry("additionalProperties", false);
        assertThat(success.succeeded()).isTrue();
        assertThat(success.result().get("content")).isEqualTo("Only this reference is exposed.");
        assertThat(rejected.succeeded()).isFalse();
        assertThat(rejected.errorMessage()).contains("not part of this Run binding");
    }

    @Test
    void injectsImmutableScriptIdentityInsteadOfAcceptingItFromTheModel() throws Exception {
        String markdown = """
                ---
                requirements:
                  network: none
                ---
                # Script Skill
                """;
        SkillRunBinding binding = installAndResolve(
                "script-skill", markdown, Map.of("scripts/run.py", "print('sandboxed')"));
        when(nodes.get("node-1", actor)).thenReturn(compatibleNode());
        ToolDescriptor descriptor = provider.discover(new ToolDiscoveryRequest(
                        "run-1", "node-1", List.of(), List.of(), List.of(binding), actor)).stream()
                .filter(tool -> "script.run".equals(tool.providerToolName()))
                .findFirst()
                .orElseThrow();
        when(nodes.callToolForRun(eq("run-1"), eq("call-1"), eq("node-1"), eq("skill.script.run"), any(), eq(actor)))
                .thenReturn(new NodeToolCallResult(
                        "nodeinv-1", "node-1", "skill.script.run", "APPROVAL_REQUIRED",
                        Map.of("approvalId", "approval-1"), null));

        var outcome = provider.invoke(new ToolInvocationRequest(
                "run-1", "call-1", resolved(descriptor),
                Map.of(
                        "skillId", "attacker-skill",
                        "entrypoint", "scripts/attacker.py",
                        "arguments", List.of("--check")),
                30,
                CodingWorkspaceScope.from(null),
                actor));

        ArgumentCaptor<CallNodeToolCommand> command = ArgumentCaptor.forClass(CallNodeToolCommand.class);
        verify(nodes).callToolForRun(
                eq("run-1"), eq("call-1"), eq("node-1"), eq("skill.script.run"), command.capture(), eq(actor));
        assertThat(command.getValue().arguments())
                .containsEntry("skillId", "script-skill")
                .containsEntry("releaseDigest", binding.digest())
                .containsEntry("entrypoint", "scripts/run.py")
                .containsEntry("runtime", "python")
                .containsEntry("network", "none");
        assertThat(descriptor.inputSchema().toString()).doesNotContain("skillId", "entrypoint", "bundleDigest", "nodeId");
        assertThat(descriptor.description())
                .contains("runtime", "fixed by the binding", "Requires approval");
        assertThat(descriptor.inputSchema()).containsEntry("additionalProperties", false);
        assertThat(outcome.requiresApproval()).isTrue();
        assertThat(outcome.approvalId()).isEqualTo("approval-1");
    }

    private SkillRunBinding installAndResolve(String id, String markdown, Map<String, String> files) throws Exception {
        Path directory = temporaryDirectory.resolve("data/skills").resolve(id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), markdown);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = directory.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", id);
        metadata.put("name", id);
        metadata.put("description", "test skill");
        metadata.put("enabled", true);
        metadata.put("installedAt", Instant.parse("2026-08-01T00:00:00Z"));
        metadata.put("sourceRepository", "fixture/skills");
        metadata.put("sourceUrl", "https://example.test/skills");
        metadata.put("ref", "main");
        metadata.put("path", id);
        metadata.put("fileCount", files.size() + 1);
        metadata.put("sizeBytes", 100);
        objectMapper.writeValue(directory.resolve(".cycbercompany-skill.json").toFile(), metadata);
        return catalog.resolveForRun(List.of(id)).getFirst();
    }

    private NodeDetailView compatibleNode() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        NodeConnectionView connection = new NodeConnectionView(
                "node-1", "node", "host", "Windows", "amd64", "1",
                NodeKind.REGISTERED, "revision", Map.of("python", "python:3.12-alpine"),
                Set.of("skill.bundle.v1", "skill.resource.read.v1", "skill.script.runtime.v1", "skill.script.python.v1"),
                true, NodeStatus.ONLINE, now, now, now);
        NodeToolView tool = new NodeToolView(
                1L, "node-1", "skill.script.run", "1", "sandboxed script", RiskLevel.HIGH,
                true, true, "{}", now, now);
        return new NodeDetailView(connection, List.of(tool));
    }

    private static ResolvedToolBinding resolved(ToolDescriptor descriptor) {
        return new ResolvedToolBinding(
                descriptor.bindingId(), "model_tool", descriptor.logicalName(), descriptor.providerId(),
                descriptor.providerToolName(), descriptor.description(), descriptor.riskLevel(),
                descriptor.requiresApproval(), descriptor.inputSchema(), descriptor.attributes());
    }

}
