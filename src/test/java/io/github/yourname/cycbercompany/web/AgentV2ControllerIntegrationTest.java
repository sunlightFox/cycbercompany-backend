package io.github.yourname.cycbercompany.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-v2-controller;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
@AutoConfigureMockMvc
class AgentV2ControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsValidatesPublishesAndReadsAgentThroughV2Api() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.set("manifest", validManifest());
        request.put("visibility", "PRIVATE");
        String createdBody = mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentPublishedVersionId").doesNotExist())
                .andExpect(jsonPath("$.latestDraft.state").value("DRAFT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var created = objectMapper.readTree(createdBody);
        String agentId = created.path("id").asText();
        String versionId = created.path("latestDraft").path("id").asText();
        long revision = created.path("latestDraft").path("revision").asLong();

        ObjectNode update = objectMapper.createObjectNode();
        ObjectNode updatedManifest = validManifest();
        ((ObjectNode) updatedManifest.path("persona")).put("mission", "Find defects and explain the impact.");
        update.set("manifest", updatedManifest);
        update.put("expectedRevision", revision);
        mvc.perform(put("/api/v2/agents/{agentId}/drafts/{versionId}/manifest", agentId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision + 1));
        mvc.perform(put("/api/v2/agents/{agentId}/drafts/{versionId}/manifest", agentId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.actualRevision").value(revision + 1));

        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/validate", agentId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.manifestDigest").value(org.hamcrest.Matchers.startsWith("sha256:")));
        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", agentId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PUBLISHED"));
        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", agentId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId))
                .andExpect(jsonPath("$.state").value("PUBLISHED"));
        mvc.perform(get("/api/v2/agents/{agentId}/versions/{versionId}", agentId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId))
                .andExpect(jsonPath("$.manifest.persona.role").value("Code reviewer"));
        mvc.perform(get("/api/v2/agents/{agentId}", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPublishedVersionId").value(versionId))
                .andExpect(jsonPath("$.currentPublishedVersion.manifest.persona.role").value("Code reviewer"));

        assertThat(agentId).isNotBlank();
    }

    @Test
    void invalidManifestReturnsStructuredUnprocessableEntity() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.set("manifest", objectMapper.createObjectNode().put("schemaVersion", 2));
        request.put("visibility", "PRIVATE");

        mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENT_MANIFEST_INVALID"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unavailableSkillMcpAndKnowledgeReferencesBlockValidationAndPublish() throws Exception {
        ObjectNode manifest = validManifest();
        ((ArrayNode) manifest.path("capabilities").path("skills"))
                .addObject().put("id", "missing-skill");
        ((ArrayNode) manifest.path("capabilities").path("mcpConnections"))
                .addObject().put("id", "missing-mcp");
        ((ArrayNode) manifest.path("capabilities").path("knowledgeBases"))
                .addObject().put("id", "missing-knowledge-base");
        ObjectNode request = objectMapper.createObjectNode();
        request.set("manifest", manifest);
        request.put("visibility", "PRIVATE");
        var created = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String agentId = created.path("id").asText();
        String versionId = created.path("latestDraft").path("id").asText();

        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/validate", agentId, versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("Skill references are not available"))))
                .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("MCP connection is not available"))))
                .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("Knowledge base is not available"))));
        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", agentId, versionId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENT_MANIFEST_INVALID"));
    }

    @Test
    void updatesAgentSettingsAndReportsStaleIdentityRevision() throws Exception {
        ObjectNode create = objectMapper.createObjectNode();
        create.set("manifest", validManifest());
        create.put("visibility", "PRIVATE");
        var created = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(create)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String agentId = created.path("id").asText();
        long revision = created.path("revision").asLong();

        ObjectNode settings = objectMapper.createObjectNode()
                .put("visibility", "TEAM")
                .put("status", "DISABLED")
                .put("expectedRevision", revision);
        mvc.perform(patch("/api/v2/agents/{agentId}", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(settings)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("TEAM"))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.revision").value(revision + 1));
        mvc.perform(patch("/api/v2/agents/{agentId}", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(settings)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_IDENTITY_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.actualRevision").value(revision + 1));
    }

    @Test
    void validatesCollaboratorReadinessBeforePublishing() throws Exception {
        ObjectNode targetRequest = objectMapper.createObjectNode();
        targetRequest.set("manifest", validManifest());
        targetRequest.put("visibility", "PRIVATE");
        var target = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(targetRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String targetAgentId = target.path("id").asText();
        String targetVersionId = target.path("latestDraft").path("id").asText();

        ObjectNode parentManifest = validManifest();
        ((ObjectNode) parentManifest.path("identity")).put("displayName", "Coordinator");
        ((ArrayNode) parentManifest.path("capabilities").path("collaborators"))
                .addObject()
                .put("agentId", targetAgentId)
                .put("mode", "AS_TOOL")
                .put("when", "When an independent review is needed");
        ObjectNode parentRequest = objectMapper.createObjectNode();
        parentRequest.set("manifest", parentManifest);
        parentRequest.put("visibility", "PRIVATE");
        var parent = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(parentRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String parentAgentId = parent.path("id").asText();
        String parentVersionId = parent.path("latestDraft").path("id").asText();

        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/validate", parentAgentId, parentVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value(
                        org.hamcrest.Matchers.containsString("has no published version")));

        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", targetAgentId, targetVersionId))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/validate", parentAgentId, parentVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", parentAgentId, parentVersionId))
                .andExpect(status().isOk());

        ObjectNode handoffManifest = validManifest();
        ((ObjectNode) handoffManifest.path("identity")).put("displayName", "Handoff coordinator");
        ((ArrayNode) handoffManifest.path("capabilities").path("collaborators"))
                .addObject()
                .put("agentId", targetAgentId)
                .put("mode", "HANDOFF")
                .put("when", "When the specialist should own the task");
        ObjectNode handoffRequest = objectMapper.createObjectNode();
        handoffRequest.set("manifest", handoffManifest);
        handoffRequest.put("visibility", "PRIVATE");
        var handoff = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(handoffRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mvc.perform(post(
                        "/api/v2/agents/{agentId}/drafts/{versionId}/validate",
                        handoff.path("id").asText(),
                        handoff.path("latestDraft").path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish",
                        handoff.path("id").asText(), handoff.path("latestDraft").path("id").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsSelfCollaboratorReferenceWhenUpdatingDraft() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.set("manifest", validManifest());
        request.put("visibility", "PRIVATE");
        var created = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        String agentId = created.path("id").asText();
        String versionId = created.path("latestDraft").path("id").asText();
        long revision = created.path("latestDraft").path("revision").asLong();
        ObjectNode selfReferencingManifest = validManifest();
        ((ArrayNode) selfReferencingManifest.path("capabilities").path("collaborators"))
                .addObject()
                .put("agentId", agentId)
                .put("mode", "HANDOFF")
                .put("when", "When this Agent should handle its own task");
        ObjectNode update = objectMapper.createObjectNode();
        update.set("manifest", selfReferencingManifest);
        update.put("expectedRevision", revision);

        mvc.perform(put("/api/v2/agents/{agentId}/drafts/{versionId}/manifest", agentId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.detail").value("An Agent cannot collaborate with itself."));
    }

    @Test
    void rejectsCollaboratorCycleBeforePublishing() throws Exception {
        ObjectNode firstRequest = objectMapper.createObjectNode();
        firstRequest.set("manifest", validManifest());
        firstRequest.put("visibility", "TEAM");
        var first = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(firstRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String firstId = first.path("id").asText();
        String firstVersionId = first.path("latestDraft").path("id").asText();

        ObjectNode secondManifest = validManifest();
        ((ObjectNode) secondManifest.path("identity")).put("displayName", "Cycle partner");
        ((ArrayNode) secondManifest.path("capabilities").path("collaborators"))
                .addObject()
                .put("agentId", firstId)
                .put("mode", "AS_TOOL")
                .put("when", "When the first Agent needs a review");
        ObjectNode secondRequest = objectMapper.createObjectNode();
        secondRequest.set("manifest", secondManifest);
        secondRequest.put("visibility", "TEAM");
        var second = objectMapper.readTree(mvc.perform(post("/api/v2/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(secondRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String secondId = second.path("id").asText();
        String secondVersionId = second.path("latestDraft").path("id").asText();

        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", firstId, firstVersionId))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", secondId, secondVersionId))
                .andExpect(status().isOk());

        var firstDraft = objectMapper.readTree(mvc.perform(post("/api/v2/agents/{agentId}/drafts", firstId))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());
        String firstDraftVersionId = firstDraft.path("id").asText();

        ObjectNode cycleManifest = validManifest();
        ((ObjectNode) cycleManifest.path("identity")).put("displayName", "Cycle root");
        ((ArrayNode) cycleManifest.path("capabilities").path("collaborators"))
                .addObject()
                .put("agentId", secondId)
                .put("mode", "AS_TOOL")
                .put("when", "When the second Agent should review");
        long revision = firstDraft.path("revision").asLong();
        ObjectNode update = objectMapper.createObjectNode();
        update.set("manifest", cycleManifest);
        update.put("expectedRevision", revision);
        mvc.perform(put("/api/v2/agents/{agentId}/drafts/{versionId}/manifest", firstId, firstDraftVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/validate", firstId, firstDraftVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value(
                        org.hamcrest.Matchers.containsString("Collaborator cycle detected")));

        mvc.perform(post("/api/v2/agents/{agentId}/drafts/{versionId}/publish", firstId, firstDraftVersionId))
                .andExpect(status().isUnprocessableEntity());
    }

    private ObjectNode validManifest() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 2);
        root.putObject("identity")
                .put("displayName", "Code Review Partner")
                .put("description", "Reviews changes before merge.");
        ObjectNode persona = root.putObject("persona");
        persona.put("role", "Code reviewer");
        persona.put("mission", "Find material defects before merge.");
        persona.putArray("responsibilities").add("Review correctness and security.");
        persona.putArray("boundaries").add("Do not modify code during review-only requests.");
        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("model").put("defaultProfileId", "minimax-m3");
        capabilities.putArray("tools").addObject().put("id", "local_time");
        capabilities.putArray("skills");
        capabilities.putArray("mcpConnections");
        capabilities.putArray("knowledgeBases");
        capabilities.putArray("collaborators");
        ObjectNode memory = root.putObject("memory");
        memory.put("mode", "CONVERSATION");
        memory.putObject("shortTerm").put("strategy", "HYBRID").put("maxContextTokens", 16000);
        ObjectNode longTerm = memory.putObject("longTerm");
        longTerm.put("enabled", false);
        longTerm.putArray("categories");
        longTerm.put("writeMode", "EXPLICIT_ONLY");
        longTerm.put("retrievalMode", "HYBRID");
        longTerm.put("topK", 3);
        longTerm.put("sensitiveDataPolicy", "REJECT");
        root.putObject("runtime")
                .put("autonomy", "ASSIST")
                .put("planning", "IMPLICIT")
                .put("maxSteps", 40)
                .put("timeoutSeconds", 1800);
        ObjectNode safety = root.putObject("safety");
        safety.put("approvalPreset", "CONSERVATIVE");
        safety.putArray("inputGuardrails");
        safety.putArray("outputGuardrails");
        return root;
    }
}
