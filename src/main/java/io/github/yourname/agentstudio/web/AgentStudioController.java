package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.agent.AgentCatalog;
import io.github.yourname.agentstudio.agent.CreateAgentCommand;
import io.github.yourname.agentstudio.agent.UpdateAgentCommand;
import io.github.yourname.agentstudio.conversation.ConversationAttachmentService;
import io.github.yourname.agentstudio.conversation.ConversationService;
import io.github.yourname.agentstudio.conversation.CreateConversationCommand;
import io.github.yourname.agentstudio.knowledge.CreateKnowledgeBaseCommand;
import io.github.yourname.agentstudio.knowledge.IngestDocumentCommand;
import io.github.yourname.agentstudio.knowledge.KnowledgeCommandService;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.knowledge.KnowledgeSearchCommand;
import io.github.yourname.agentstudio.knowledge.UpdateKnowledgeBaseCommand;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.model.SetDefaultModelCommand;
import io.github.yourname.agentstudio.model.TestModelCommand;
import io.github.yourname.agentstudio.model.UpdateModelStatusCommand;
import io.github.yourname.agentstudio.model.UpsertModelProfileCommand;
import io.github.yourname.agentstudio.node.CallNodeToolCommand;
import io.github.yourname.agentstudio.node.BootstrapLocalExecutorCommand;
import io.github.yourname.agentstudio.node.CreateNodeRegistrationTokenCommand;
import io.github.yourname.agentstudio.node.DecideNodeToolApprovalCommand;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.node.RegisterNodeCommand;
import io.github.yourname.agentstudio.node.UpdateNodeCommand;
import io.github.yourname.agentstudio.node.UpdateNodeToolCommand;
import io.github.yourname.agentstudio.execution.ExecutionSettingsService;
import io.github.yourname.agentstudio.execution.UpdateExecutionSettingsCommand;
import io.github.yourname.agentstudio.mcp.CreateMcpConnectionCommand;
import io.github.yourname.agentstudio.mcp.CallMcpToolCommand;
import io.github.yourname.agentstudio.mcp.InstallNpmMcpServerCommand;
import io.github.yourname.agentstudio.mcp.McpConnectionService;
import io.github.yourname.agentstudio.mcp.McpRepositoryService;
import io.github.yourname.agentstudio.mcp.SearchMcpRepositoriesCommand;
import io.github.yourname.agentstudio.mcp.UpdateMcpConnectionCommand;
import io.github.yourname.agentstudio.mcp.UpdateMcpToolCommand;
import io.github.yourname.agentstudio.mcp.UpsertMcpToolCommand;
import io.github.yourname.agentstudio.orchestration.CreateRunCommand;
import io.github.yourname.agentstudio.orchestration.ConversationQueueQueryService;
import io.github.yourname.agentstudio.orchestration.RunCommandService;
import io.github.yourname.agentstudio.orchestration.RunEventPublisher;
import io.github.yourname.agentstudio.orchestration.RunQueryService;
import io.github.yourname.agentstudio.orchestration.RunWorkflowCheckpointService;
import io.github.yourname.agentstudio.security.CurrentActorProvider;
import io.github.yourname.agentstudio.skill.DiscoverRepositorySkillsCommand;
import io.github.yourname.agentstudio.skill.ClawHubSkillService;
import io.github.yourname.agentstudio.skill.CreateSkillCommand;
import io.github.yourname.agentstudio.skill.InstallClawHubSkillCommand;
import io.github.yourname.agentstudio.skill.InstallSkillCommand;
import io.github.yourname.agentstudio.skill.SearchSkillRepositoriesCommand;
import io.github.yourname.agentstudio.skill.SkillCatalog;
import io.github.yourname.agentstudio.skill.SkillExperienceService;
import io.github.yourname.agentstudio.skill.SkillPreflightCommand;
import io.github.yourname.agentstudio.skill.SkillRepositoryService;
import io.github.yourname.agentstudio.skill.UpdateSkillContentCommand;
import io.github.yourname.agentstudio.skill.UpdateSkillCommand;
import io.github.yourname.agentstudio.tool.ToolCatalog;
import io.github.yourname.agentstudio.tool.ToolRouter;
import io.github.yourname.agentstudio.tool.DecideToolApprovalCommand;
import io.github.yourname.agentstudio.tool.WebSearchCommand;
import io.github.yourname.agentstudio.tool.WebSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
class AgentStudioController {

    private final CurrentActorProvider actors;
    private final ConversationService conversations;
    private final ConversationAttachmentService attachments;
    private final ModelCatalog models;
    private final AgentCatalog agents;
    private final ToolCatalog tools;
    private final ToolRouter toolRouter;
    private final WebSearchService webSearch;
    private final SkillCatalog skills;
    private final SkillExperienceService skillExperience;
    private final SkillRepositoryService skillRepositories;
    private final ClawHubSkillService clawHubSkills;
    private final McpConnectionService mcpConnections;
    private final McpRepositoryService mcpRepositories;
    private final NodeService nodes;
    private final ExecutionSettingsService executionSettings;
    private final KnowledgeCommandService knowledgeCommands;
    private final KnowledgeQueryService knowledgeQueries;
    private final RunCommandService runCommands;
    private final RunQueryService runQueries;
    private final RunWorkflowCheckpointService workflowCheckpoints;
    private final ConversationQueueQueryService conversationQueues;
    private final RunEventPublisher runEvents;

    private static boolean isLoopbackRequest(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    AgentStudioController(
            CurrentActorProvider actors,
            ConversationService conversations,
            ConversationAttachmentService attachments,
            ModelCatalog models,
            AgentCatalog agents,
            ToolCatalog tools,
            ToolRouter toolRouter,
            WebSearchService webSearch,
            SkillCatalog skills,
            SkillExperienceService skillExperience,
            SkillRepositoryService skillRepositories,
            ClawHubSkillService clawHubSkills,
            McpConnectionService mcpConnections,
            McpRepositoryService mcpRepositories,
            NodeService nodes,
            ExecutionSettingsService executionSettings,
            KnowledgeCommandService knowledgeCommands,
            KnowledgeQueryService knowledgeQueries,
            RunCommandService runCommands,
            RunQueryService runQueries,
            RunWorkflowCheckpointService workflowCheckpoints,
            ConversationQueueQueryService conversationQueues,
            RunEventPublisher runEvents) {
        this.actors = actors;
        this.conversations = conversations;
        this.attachments = attachments;
        this.models = models;
        this.agents = agents;
        this.tools = tools;
        this.toolRouter = toolRouter;
        this.webSearch = webSearch;
        this.skills = skills;
        this.skillExperience = skillExperience;
        this.skillRepositories = skillRepositories;
        this.clawHubSkills = clawHubSkills;
        this.mcpConnections = mcpConnections;
        this.mcpRepositories = mcpRepositories;
        this.nodes = nodes;
        this.executionSettings = executionSettings;
        this.knowledgeCommands = knowledgeCommands;
        this.knowledgeQueries = knowledgeQueries;
        this.runCommands = runCommands;
        this.runQueries = runQueries;
        this.workflowCheckpoints = workflowCheckpoints;
        this.conversationQueues = conversationQueues;
        this.runEvents = runEvents;
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    Object createConversation(@RequestBody CreateConversationCommand command, HttpServletRequest request) {
        return conversations.create(command, actors.current(request));
    }

    @GetMapping("/conversations/{id}")
    Object getConversation(@PathVariable String id, HttpServletRequest request) {
        return conversations.get(id, actors.current(request));
    }

    @PostMapping(path = "/conversations/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Object uploadConversationAttachments(
            @PathVariable String id,
            @RequestPart("files") List<MultipartFile> files,
            HttpServletRequest request) {
        return attachments.upload(id, files, actors.current(request));
    }

    @GetMapping("/models")
    Object listModels() {
        return models.list();
    }

    @GetMapping("/execution-settings")
    Object getExecutionSettings(HttpServletRequest request) {
        return executionSettings.get(actors.current(request));
    }

    @PatchMapping("/execution-settings")
    Object updateExecutionSettings(
            @Valid @RequestBody UpdateExecutionSettingsCommand command,
            HttpServletRequest request) {
        return executionSettings.update(command, actors.current(request));
    }

    @PostMapping("/local-executor/bootstrap")
    Object bootstrapLocalExecutor(
            @RequestBody(required = false) BootstrapLocalExecutorCommand command,
            HttpServletRequest request) {
        if (!isLoopbackRequest(request)) {
            throw new IllegalArgumentException("The local executor may only be provisioned from this computer.");
        }
        return nodes.bootstrapLocalExecutor(command, actors.current(request));
    }

    @GetMapping("/models/presets")
    Object listModelPresets() {
        return models.presets();
    }

    @GetMapping("/models/settings")
    Object getModelSettings() {
        return models.settings();
    }

    @PatchMapping("/models/settings/default")
    Object setDefaultModel(@Valid @RequestBody SetDefaultModelCommand command) {
        return models.setDefault(command);
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    Object saveModel(@Valid @RequestBody UpsertModelProfileCommand command) {
        return models.save(command);
    }

    @GetMapping("/models/{id}")
    Object getModel(@PathVariable String id) {
        return models.get(id);
    }

    @PatchMapping("/models/{id}/status")
    Object updateModelStatus(@PathVariable String id, @Valid @RequestBody UpdateModelStatusCommand command) {
        return models.setEnabled(id, command.enabled());
    }

    @DeleteMapping("/models/{id}")
    ResponseEntity<Void> deleteModel(@PathVariable String id) {
        models.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/models/{id}/test")
    Object testModel(@PathVariable String id, @RequestBody(required = false) TestModelCommand command) {
        return models.test(id, command);
    }

    @GetMapping("/agents")
    Object listAgents() {
        return agents.list();
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    Object createAgent(@Valid @RequestBody CreateAgentCommand command) {
        return agents.create(command);
    }

    @PatchMapping("/agents/{id}")
    Object updateAgent(@PathVariable String id, @Valid @RequestBody UpdateAgentCommand command) {
        return agents.update(id, command);
    }

    @GetMapping("/tools")
    Object listTools(HttpServletRequest request) {
        return Stream.concat(
                Stream.concat(tools.list().stream(), mcpConnections.enabledRegisteredTools().stream()),
                nodes.enabledRegisteredTools(actors.current(request)).stream()).toList();
    }

    @PostMapping("/web-search")
    Object searchWeb(@Valid @RequestBody WebSearchCommand command) {
        var response = webSearch.searchDetailed(command);
        return Boolean.TRUE.equals(command.trace()) ? response : response.results();
    }

    @GetMapping("/skills")
    Object listSkills() {
        return skills.list();
    }

    @GetMapping("/skills/{id}")
    Object getSkill(@PathVariable String id) {
        return skills.get(id);
    }

    @PostMapping("/skills")
    @ResponseStatus(HttpStatus.CREATED)
    Object createSkill(@Valid @RequestBody CreateSkillCommand command) {
        return skills.create(command);
    }

    @PostMapping("/skills/install")
    @ResponseStatus(HttpStatus.CREATED)
    Object installSkill(@Valid @RequestBody InstallSkillCommand command) {
        return skills.install(command);
    }

    @PostMapping("/skills/install/clawhub")
    @ResponseStatus(HttpStatus.CREATED)
    Object installClawHubSkill(@Valid @RequestBody InstallClawHubSkillCommand command) {
        return skills.installClawHub(command, clawHubSkills);
    }

    @PatchMapping("/skills/{id}")
    Object updateSkill(@PathVariable String id, @Valid @RequestBody UpdateSkillCommand command) {
        return skills.setEnabled(id, command);
    }

    @org.springframework.web.bind.annotation.PutMapping("/skills/{id}/content")
    Object updateSkillContent(
            @PathVariable String id,
            @Valid @RequestBody UpdateSkillContentCommand command) {
        return skills.updateContent(id, command);
    }

    @PostMapping("/skills/preflight")
    Object preflightSkills(@RequestBody(required = false) SkillPreflightCommand command, HttpServletRequest request) {
        return skillExperience.preflight(command, actors.current(request));
    }

    @PostMapping("/skills/test")
    Object testSkills(@RequestBody(required = false) SkillPreflightCommand command, HttpServletRequest request) {
        return skillExperience.test(command, actors.current(request));
    }

    @DeleteMapping("/skills/{id}")
    ResponseEntity<Void> uninstallSkill(@PathVariable String id) {
        skills.uninstall(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/skill-repositories")
    Object listCuratedSkillRepositories() {
        return skillRepositories.curated();
    }

    @PostMapping("/skill-repositories/search")
    Object searchSkillRepositories(@RequestBody SearchSkillRepositoriesCommand command) {
        return skillRepositories.search(command);
    }

    @GetMapping("/skill-registries/clawhub/search")
    Object searchClawHubSkills(@org.springframework.web.bind.annotation.RequestParam(required = false) String query,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        return clawHubSkills.search(query, limit);
    }

    @PostMapping("/skill-repositories/discover")
    Object discoverRepositorySkills(@Valid @RequestBody DiscoverRepositorySkillsCommand command) {
        return skillRepositories.discover(command);
    }

    @GetMapping("/mcp-repositories")
    Object listCuratedMcpRepositories() {
        return mcpRepositories.curated();
    }

    @PostMapping("/mcp-repositories/search")
    Object searchMcpRepositories(@RequestBody SearchMcpRepositoriesCommand command) {
        return mcpRepositories.search(command);
    }

    @GetMapping("/mcp-connections")
    Object listMcpConnections() {
        return mcpConnections.listConnections();
    }

    @PostMapping("/mcp-connections")
    @ResponseStatus(HttpStatus.CREATED)
    Object createMcpConnection(@Valid @RequestBody CreateMcpConnectionCommand command) {
        return mcpConnections.create(command);
    }

    @PostMapping("/mcp-connections/install-npm")
    @ResponseStatus(HttpStatus.CREATED)
    Object installNpmMcpConnection(@Valid @RequestBody InstallNpmMcpServerCommand command) {
        return mcpConnections.installNpm(command);
    }

    @GetMapping("/mcp-connections/{id}")
    Object getMcpConnection(@PathVariable String id) {
        return mcpConnections.getConnection(id);
    }

    @PatchMapping("/mcp-connections/{id}")
    Object updateMcpConnection(@PathVariable String id, @Valid @RequestBody UpdateMcpConnectionCommand command) {
        return mcpConnections.update(id, command);
    }

    @DeleteMapping("/mcp-connections/{id}")
    ResponseEntity<Void> deleteMcpConnection(@PathVariable String id) {
        mcpConnections.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mcp-connections/{id}/enable")
    Object enableMcpConnection(@PathVariable String id) {
        return mcpConnections.setConnectionEnabled(id, true);
    }

    @PostMapping("/mcp-connections/{id}/disable")
    Object disableMcpConnection(@PathVariable String id) {
        return mcpConnections.setConnectionEnabled(id, false);
    }

    @PostMapping("/mcp-connections/{id}/refresh-tools")
    Object refreshMcpTools(@PathVariable String id) {
        return mcpConnections.refreshTools(id);
    }

    @GetMapping("/mcp-connections/{id}/tools")
    Object listMcpTools(@PathVariable String id) {
        return mcpConnections.listTools(id);
    }

    @PostMapping("/mcp-connections/{id}/tools")
    @ResponseStatus(HttpStatus.CREATED)
    Object upsertMcpTool(@PathVariable String id, @Valid @RequestBody UpsertMcpToolCommand command) {
        return mcpConnections.upsertTool(id, command);
    }

    @PatchMapping("/mcp-connections/{id}/tools/{toolName}")
    Object updateMcpTool(
            @PathVariable String id,
            @PathVariable String toolName,
            @Valid @RequestBody UpdateMcpToolCommand command) {
        return mcpConnections.updateTool(id, toolName, command);
    }

    @PostMapping("/mcp-connections/{id}/tools/{toolName}/enable")
    Object enableMcpTool(@PathVariable String id, @PathVariable String toolName) {
        return mcpConnections.setToolEnabled(id, toolName, true);
    }

    @PostMapping("/mcp-connections/{id}/tools/{toolName}/disable")
    Object disableMcpTool(@PathVariable String id, @PathVariable String toolName) {
        return mcpConnections.setToolEnabled(id, toolName, false);
    }

    @PostMapping("/mcp-connections/{id}/tools/{toolName}/call")
    Object callMcpTool(
            @PathVariable String id,
            @PathVariable String toolName,
            @RequestBody(required = false) CallMcpToolCommand command,
            HttpServletRequest request) {
        return mcpConnections.callTool(id, toolName, command, null, actors.current(request));
    }

    @GetMapping("/mcp-tool-invocations")
    Object listMcpToolInvocations(HttpServletRequest request) {
        return mcpConnections.listInvocations(actors.current(request));
    }

    @DeleteMapping("/mcp-connections/{id}/tools/{toolName}")
    ResponseEntity<Void> deleteMcpTool(@PathVariable String id, @PathVariable String toolName) {
        mcpConnections.deleteTool(id, toolName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/node-registration-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    Object createNodeRegistrationToken(
            @RequestBody(required = false) CreateNodeRegistrationTokenCommand command,
            HttpServletRequest request) {
        return nodes.createRegistrationToken(command, actors.current(request));
    }

    @PostMapping("/nodes/register")
    @ResponseStatus(HttpStatus.CREATED)
    Object registerNode(@Valid @RequestBody RegisterNodeCommand command) {
        return nodes.register(command);
    }

    @GetMapping("/nodes")
    Object listNodes(HttpServletRequest request) {
        return nodes.list(actors.current(request));
    }

    @GetMapping("/nodes/{id}")
    Object getNode(@PathVariable String id, HttpServletRequest request) {
        return nodes.get(id, actors.current(request));
    }

    @PatchMapping("/nodes/{id}")
    Object updateNode(
            @PathVariable String id,
            @RequestBody UpdateNodeCommand command,
            HttpServletRequest request) {
        return nodes.update(id, command, actors.current(request));
    }

    @DeleteMapping("/nodes/{id}")
    ResponseEntity<Void> deleteNode(@PathVariable String id, HttpServletRequest request) {
        nodes.delete(id, actors.current(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/nodes/{id}/credentials/rotate")
    Object rotateNodeCredentials(@PathVariable String id, HttpServletRequest request) {
        return nodes.rotateSecret(id, actors.current(request));
    }

    @GetMapping("/nodes/{id}/tools")
    Object listNodeTools(@PathVariable String id, HttpServletRequest request) {
        return nodes.listTools(id, actors.current(request));
    }

    @PatchMapping("/nodes/{id}/tools/{toolName}")
    Object updateNodeTool(
            @PathVariable String id,
            @PathVariable String toolName,
            @RequestBody UpdateNodeToolCommand command,
            HttpServletRequest request) {
        return nodes.updateTool(id, toolName, command, actors.current(request));
    }

    @PostMapping("/nodes/{id}/tools/{toolName}/call")
    Object callNodeTool(
            @PathVariable String id,
            @PathVariable String toolName,
            @RequestBody(required = false) CallNodeToolCommand command,
            HttpServletRequest request) {
        return nodes.callTool(id, toolName, command, actors.current(request));
    }

    @PostMapping("/nodes/{id}/tools/{toolName}/approval-requests")
    @ResponseStatus(HttpStatus.CREATED)
    Object requestNodeToolApproval(
            @PathVariable String id,
            @PathVariable String toolName,
            @RequestBody(required = false) CallNodeToolCommand command,
            HttpServletRequest request) {
        return nodes.requestToolApproval(id, toolName, command, actors.current(request));
    }

    @GetMapping("/node-tool-approvals")
    Object listNodeToolApprovals(HttpServletRequest request) {
        return nodes.listToolApprovals(actors.current(request));
    }

    @PostMapping("/node-tool-approvals/{approvalId}/decision")
    Object decideNodeToolApproval(
            @PathVariable String approvalId,
            @RequestBody(required = false) DecideNodeToolApprovalCommand command,
            HttpServletRequest request) {
        var actor = actors.current(request);
        var decision = nodes.decideToolApproval(approvalId, command, actor);
        runCommands.resumeAfterToolApproval(decision, actor);
        return decision;
    }

    @GetMapping("/tool-approvals")
    Object listToolApprovals(HttpServletRequest request) {
        return toolRouter.listApprovals(actors.current(request));
    }

    @PostMapping("/tool-approvals/{approvalId}/decision")
    Object decideToolApproval(
            @PathVariable String approvalId,
            @RequestBody(required = false) DecideToolApprovalCommand command,
            HttpServletRequest request) {
        var actor = actors.current(request);
        var decision = toolRouter.decideApproval(approvalId, command, actor);
        runCommands.resumeAfterToolApproval(decision, actor);
        return decision;
    }

    @PostMapping("/knowledge-bases")
    @ResponseStatus(HttpStatus.CREATED)
    Object createKnowledgeBase(@Valid @RequestBody CreateKnowledgeBaseCommand command, HttpServletRequest request) {
        return knowledgeCommands.create(command, actors.current(request));
    }

    @GetMapping("/knowledge-settings")
    Object getKnowledgeSettings() {
        return knowledgeQueries.settings();
    }

    @GetMapping("/knowledge-bases")
    Object listKnowledgeBases(HttpServletRequest request) {
        return knowledgeQueries.list(actors.current(request));
    }

    @GetMapping("/knowledge-bases/{id}")
    Object getKnowledgeBase(@PathVariable String id, HttpServletRequest request) {
        return knowledgeQueries.get(id, actors.current(request));
    }

    @PatchMapping("/knowledge-bases/{id}")
    Object updateKnowledgeBase(
            @PathVariable String id,
            @Valid @RequestBody UpdateKnowledgeBaseCommand command,
            HttpServletRequest request) {
        return knowledgeCommands.update(id, command, actors.current(request));
    }

    @DeleteMapping("/knowledge-bases/{id}")
    ResponseEntity<Void> deleteKnowledgeBase(@PathVariable String id, HttpServletRequest request) {
        knowledgeCommands.deleteKnowledgeBase(id, actors.current(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/knowledge-bases/{id}/rebuild-index")
    Object rebuildKnowledgeBaseIndex(@PathVariable String id, HttpServletRequest request) {
        return knowledgeCommands.rebuildKnowledgeBase(id, actors.current(request));
    }

    @PostMapping("/knowledge-bases/{id}/clear-documents")
    Object clearKnowledgeDocuments(@PathVariable String id, HttpServletRequest request) {
        return knowledgeCommands.clearDocuments(id, actors.current(request));
    }

    @PostMapping("/knowledge-bases/{id}/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object ingestDocument(@PathVariable String id, @Valid @RequestBody IngestDocumentCommand command, HttpServletRequest request) {
        return knowledgeCommands.ingest(id, command, actors.current(request));
    }

    @PostMapping(path = "/knowledge-bases/{id}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object uploadKnowledgeDocument(
            @PathVariable String id,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) {
        return knowledgeCommands.ingestFile(id, file, actors.current(request));
    }

    @PostMapping(path = "/knowledge-bases/{id}/documents/batch-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object uploadKnowledgeDocuments(
            @PathVariable String id,
            @RequestPart("files") List<MultipartFile> files,
            HttpServletRequest request) {
        return knowledgeCommands.ingestFiles(id, files, actors.current(request));
    }

    @GetMapping("/knowledge-bases/{id}/documents")
    Object listKnowledgeDocuments(@PathVariable String id, HttpServletRequest request) {
        return knowledgeQueries.listDocuments(id, actors.current(request));
    }

    @DeleteMapping("/knowledge-bases/{id}/documents/{documentId}")
    ResponseEntity<Void> deleteKnowledgeDocument(
            @PathVariable String id,
            @PathVariable String documentId,
            HttpServletRequest request) {
        knowledgeCommands.deleteDocument(id, documentId, actors.current(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/knowledge-bases/{id}/documents/{documentId}/rebuild-index")
    Object rebuildKnowledgeDocumentIndex(
            @PathVariable String id,
            @PathVariable String documentId,
            HttpServletRequest request) {
        return knowledgeCommands.rebuildDocument(id, documentId, actors.current(request));
    }

    @GetMapping("/knowledge-bases/{id}/documents/{documentId}/chunks")
    Object listKnowledgeDocumentChunks(
            @PathVariable String id,
            @PathVariable String documentId,
            HttpServletRequest request) {
        return knowledgeQueries.listDocumentChunks(id, documentId, actors.current(request));
    }

    @GetMapping("/knowledge-bases/{id}/stats")
    Object getKnowledgeStats(@PathVariable String id, HttpServletRequest request) {
        return knowledgeQueries.stats(id, actors.current(request));
    }

    @PostMapping("/knowledge-search")
    Object searchKnowledge(@Valid @RequestBody KnowledgeSearchCommand command, HttpServletRequest request) {
        return knowledgeQueries.search(command, actors.current(request));
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object createRun(@Valid @RequestBody CreateRunCommand command, HttpServletRequest request) {
        return runCommands.create(command, actors.current(request));
    }

    @GetMapping("/conversations/{id}/queue")
    Object getConversationQueue(@PathVariable String id, HttpServletRequest request) {
        return conversationQueues.get(id, actors.current(request));
    }

    @PostMapping("/runs/{id}/cancel")
    Object cancelRun(@PathVariable String id, HttpServletRequest request) {
        return runCommands.cancel(id, actors.current(request));
    }

    @PostMapping("/runs/{id}/reconcile")
    Object reconcileRunNodeInvocations(@PathVariable String id, HttpServletRequest request) {
        var actor = actors.current(request);
        // 先解析 Run 本身，统一执行租户校验；不能让猜测到的 runId 成为查询其他租户节点命令的入口。
        runQueries.get(id, actor);
        return nodes.requestRunReconciliation(id, actor);
    }

    @GetMapping("/runs/{id}")
    Object getRun(@PathVariable String id, HttpServletRequest request) {
        return runQueries.get(id, actors.current(request));
    }

    @GetMapping("/runs/{id}/workflow")
    Object getRunWorkflowCheckpoint(@PathVariable String id, HttpServletRequest request) {
        var actor = actors.current(request);
        // 先解析 Run 本身，确保不存在的 Run 与没有检查点的旧数据都使用统一的租户校验。
        runQueries.get(id, actor);
        return workflowCheckpoints.get(id, actor);
    }

    @GetMapping("/runs/{id}/tool-invocations")
    Object listRunToolInvocations(@PathVariable String id, HttpServletRequest request) {
        var actor = actors.current(request);
        // Resolve the run first so invocation IDs cannot become a cross-tenant
        // enumeration surface.
        runQueries.get(id, actor);
        return nodes.listRunInvocations(id, actor);
    }

    @GetMapping("/runs/{id}/coding-evidence")
    Object codingEvidence(@PathVariable String id, HttpServletRequest request) {
        var actor = actors.current(request);
        // 必须先按当前租户查询任务。否则仅凭猜测 runId 就可能枚举其他租户的摘要。
        runQueries.get(id, actor);
        return nodes.codingEvidence(id, actor);
    }

    @GetMapping("/runs/{id}/coding-quality")
    Object codingQuality(@PathVariable String id, HttpServletRequest request) {
        var actor = actors.current(request);
        // 与证据接口使用同一层租户校验，防止通过评分结果枚举别人的任务。
        runQueries.get(id, actor);
        return nodes.codingQuality(id, actor);
    }

    @GetMapping(path = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamRunEvents(
            @PathVariable String id,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
            HttpServletRequest request) throws IOException {
        var actor = actors.current(request);
        var emitter = new SseEmitter(0L);
        for (var event : runEvents.replay(id, lastEventId == null ? 0 : lastEventId, actor)) {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type().name()).data(event));
            if (runEvents.isTerminal(event.type())) {
                emitter.complete();
                return emitter;
            }
        }
        runEvents.register(id, emitter);
        return emitter;
    }
}
