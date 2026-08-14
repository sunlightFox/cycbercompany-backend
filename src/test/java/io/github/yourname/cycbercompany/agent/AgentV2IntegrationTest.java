package io.github.yourname.cycbercompany.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-v2-integration;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
class AgentV2IntegrationTest {

    @Autowired
    private AgentV2Service agents;

    @Autowired
    private AgentRuntimeDefinitionService runtimeDefinitions;

    @Autowired
    private AgentDefinitionRepository legacyAgents;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentEvaluationRepository evaluations;

    @Test
    void startupImportsLegacyAgentsWithoutChangingTheirRuntimeSnapshot() {
        AgentDefinitionEntity legacy = legacyAgents.findById("default-assistant").orElseThrow();
        AgentV2View imported = agents.get("default-assistant", "local", "local-user");
        AgentRuntimeDefinition runtime = runtimeDefinitions.resolve("default-assistant", "local", "local-user");

        assertThat(imported.currentPublishedVersionId()).isNotBlank();
        assertThat(imported.currentPublishedVersion().state()).isEqualTo(AgentVersionState.PUBLISHED);
        assertThat(runtime.systemPrompt()).isEqualTo(legacy.systemPrompt());
        assertThat(runtime.toolAllowList()).isEqualTo(legacy.toolAllowList());
        assertThat(runtime.defaultModelProfileId()).isEqualTo(legacy.defaultModelProfileId());
    }

    @Test
    void draftPublishAndRepublishKeepRuntimeVersionsImmutable() {
        var created = agents.create(
                new CreateAgentV2Command(manifestMap(AgentManifestTestData.valid(objectMapper)), "PRIVATE"),
                "tenant-v2",
                "owner-v2");

        assertThat(created.currentPublishedVersionId()).isNull();
        assertThat(created.latestDraft()).isNotNull();
        assertThatThrownBy(() -> runtimeDefinitions.resolve(created.id(), "tenant-v2", "owner-v2"))
                .hasMessageContaining("no published version");

        AgentVersionView firstPublished = agents.publish(
                created.id(), created.latestDraft().id(), "tenant-v2", "owner-v2");
        AgentVersionView repeatedPublish = agents.publish(
                created.id(), created.latestDraft().id(), "tenant-v2", "owner-v2");
        AgentRuntimeDefinition firstRuntime = runtimeDefinitions.resolve(created.id(), "tenant-v2", "owner-v2");

        assertThat(firstPublished.state()).isEqualTo(AgentVersionState.PUBLISHED);
        assertThat(repeatedPublish.id()).isEqualTo(firstPublished.id());
        assertThat(repeatedPublish.manifestDigest()).isEqualTo(firstPublished.manifestDigest());
        assertThat(firstRuntime.agentVersionId()).isEqualTo(firstPublished.id());
        assertThat(firstRuntime.agentManifestDigest()).isEqualTo(firstPublished.manifestDigest());
        assertThat(firstRuntime.defaultModelProfileId()).isEqualTo("model-review");
        assertThat(firstRuntime.toolAllowList()).isEqualTo("git.diff");
        assertThat(legacyAgents.findById(created.id())).isPresent();

        AgentVersionView secondDraft = agents.createDraft(created.id(), "tenant-v2", "owner-v2");
        var changedManifest = objectMapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(secondDraft.manifest()).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changedManifest.path("persona"))
                .put("mission", "Find defects and propose focused fixes.");
        AgentVersionView updatedDraft = agents.updateDraft(
                created.id(),
                secondDraft.id(),
                new UpdateAgentManifestCommand(manifestMap(changedManifest), secondDraft.revision()),
                "tenant-v2",
                "owner-v2");
        assertThat(updatedDraft.revision()).isGreaterThan(secondDraft.revision());
        assertThatThrownBy(() -> agents.updateDraft(
                        created.id(),
                        secondDraft.id(),
                        new UpdateAgentManifestCommand(manifestMap(changedManifest), secondDraft.revision()),
                        "tenant-v2",
                        "owner-v2"))
                .isInstanceOf(AgentRevisionConflictException.class);

        AgentRuntimeDefinition beforeRepublish = runtimeDefinitions.resolve(created.id(), "tenant-v2", "owner-v2");
        assertThat(beforeRepublish.agentVersionId()).isEqualTo(firstPublished.id());
        assertThat(beforeRepublish.systemPrompt()).doesNotContain("propose focused fixes");

        AgentVersionView secondPublished = agents.publish(
                created.id(), secondDraft.id(), "tenant-v2", "owner-v2");
        AgentRuntimeDefinition afterRepublish = runtimeDefinitions.resolve(created.id(), "tenant-v2", "owner-v2");
        assertThat(secondPublished.versionNumber()).isEqualTo(2);
        assertThat(afterRepublish.agentVersionId()).isEqualTo(secondPublished.id());
        assertThat(afterRepublish.systemPrompt()).contains("propose focused fixes");
        assertThat(agents.versions(created.id(), "tenant-v2", "owner-v2"))
                .extracting(AgentVersionView::state)
                .containsExactly(AgentVersionState.PUBLISHED, AgentVersionState.PUBLISHED);
    }

    @Test
    void tenantVisibilityAndOwnerBoundariesProtectAgentDefinitions() {
        var teamAgent = agents.create(
                new CreateAgentV2Command(manifestMap(AgentManifestTestData.valid(objectMapper)), "TEAM"),
                "tenant-a",
                "owner-a");

        assertThatThrownBy(() -> agents.get(teamAgent.id(), "tenant-b", "owner-a"))
                .hasMessageContaining("Agent not found");
        agents.publish(teamAgent.id(), teamAgent.latestDraft().id(), "tenant-a", "owner-a");
        assertThatThrownBy(() -> runtimeDefinitions.resolve(teamAgent.id(), "tenant-b", "owner-a"))
                .hasMessageContaining("Agent not found");
        assertThat(runtimeDefinitions.resolve(teamAgent.id(), "tenant-a", "different-user").agentId())
                .isEqualTo(teamAgent.id());
        assertThat(agents.list("tenant-a", "different-user"))
                .extracting(AgentV2View::id)
                .contains(teamAgent.id());
        assertThatThrownBy(() -> agents.createDraft(teamAgent.id(), "tenant-a", "different-user"))
                .hasMessageContaining("Only the Agent owner");

        var privateAgent = agents.create(
                new CreateAgentV2Command(manifestMap(AgentManifestTestData.valid(objectMapper)), "PRIVATE"),
                "tenant-a",
                "owner-a");
        agents.publish(privateAgent.id(), privateAgent.latestDraft().id(), "tenant-a", "owner-a");
        assertThatThrownBy(() -> runtimeDefinitions.resolve(privateAgent.id(), "tenant-a", "different-user"))
                .hasMessageContaining("Agent not found");
        assertThat(agents.list("tenant-a", "different-user"))
                .extracting(AgentV2View::id)
                .doesNotContain(privateAgent.id());
    }

    @Test
    void ownerCanDisableAndReenableAgentWithoutLosingItFromManagementList() {
        var created = agents.create(
                new CreateAgentV2Command(manifestMap(AgentManifestTestData.valid(objectMapper)), "PRIVATE"),
                "tenant-settings",
                "owner-settings");
        agents.publish(created.id(), created.latestDraft().id(), "tenant-settings", "owner-settings");
        AgentV2View published = agents.get(created.id(), "tenant-settings", "owner-settings");

        AgentV2View disabled = agents.updateSettings(
                created.id(),
                new UpdateAgentSettingsCommand("TEAM", "DISABLED", published.revision()),
                "tenant-settings",
                "owner-settings");

        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(disabled.visibility()).isEqualTo("TEAM");
        assertThat(disabled.revision()).isGreaterThan(published.revision());
        assertThat(agents.list("tenant-settings", "different-user"))
                .extracting(AgentV2View::id)
                .contains(created.id());
        assertThatThrownBy(() -> runtimeDefinitions.resolve(
                        created.id(), "tenant-settings", "different-user"))
                .hasMessageContaining("not active");
        assertThat(legacyAgents.findById(created.id()).orElseThrow().enabled()).isFalse();
        assertThatThrownBy(() -> agents.updateSettings(
                        created.id(),
                        new UpdateAgentSettingsCommand(null, "ACTIVE", published.revision()),
                        "tenant-settings",
                        "owner-settings"))
                .isInstanceOf(AgentIdentityRevisionConflictException.class);
        assertThatThrownBy(() -> agents.updateSettings(
                        created.id(),
                        new UpdateAgentSettingsCommand(null, "ACTIVE", disabled.revision()),
                        "tenant-settings",
                        "different-user"))
                .hasMessageContaining("Only the Agent owner");

        AgentV2View reenabled = agents.updateSettings(
                created.id(),
                new UpdateAgentSettingsCommand(null, "ACTIVE", disabled.revision()),
                "tenant-settings",
                "owner-settings");
        assertThat(reenabled.status()).isEqualTo("ACTIVE");
        assertThat(runtimeDefinitions.resolve(created.id(), "tenant-settings", "different-user").agentId())
                .isEqualTo(created.id());
        assertThat(legacyAgents.findById(created.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    void publishRequiresEvaluationForTheCurrentManifestDigest() {
        var manifest = AgentManifestTestData.valid(objectMapper);
        var evaluation = manifest.putObject("evaluation");
        evaluation.putArray("suiteIds").add("role-boundary-smoke");
        evaluation.put("requiredBeforePublish", true);
        evaluation.put("minimumPassRate", 1.0);
        var created = agents.create(
                new CreateAgentV2Command(manifestMap(manifest), "PRIVATE"),
                "tenant-evaluation",
                "owner-evaluation");

        assertThatThrownBy(() -> agents.publish(
                        created.id(), created.latestDraft().id(), "tenant-evaluation", "owner-evaluation"))
                .isInstanceOf(AgentEvaluationRequiredException.class)
                .hasMessageContaining("not satisfied");

        evaluations.save(new AgentEvaluationEntity(
                java.util.UUID.randomUUID().toString(),
                "tenant-evaluation",
                created.id(),
                created.latestDraft().id(),
                created.latestDraft().manifestDigest(),
                "role-boundary-smoke",
                1.0,
                true,
                "{}",
                "owner-evaluation",
                java.time.Instant.now()));
        assertThat(agents.publish(
                        created.id(), created.latestDraft().id(), "tenant-evaluation", "owner-evaluation").state())
                .isEqualTo(AgentVersionState.PUBLISHED);
    }

    private java.util.Map<String, Object> manifestMap(com.fasterxml.jackson.databind.JsonNode value) {
        return objectMapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }
}
