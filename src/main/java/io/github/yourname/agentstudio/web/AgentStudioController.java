package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.agent.AgentCatalog;
import io.github.yourname.agentstudio.conversation.ConversationService;
import io.github.yourname.agentstudio.conversation.CreateConversationCommand;
import io.github.yourname.agentstudio.knowledge.CreateKnowledgeBaseCommand;
import io.github.yourname.agentstudio.knowledge.IngestDocumentCommand;
import io.github.yourname.agentstudio.knowledge.KnowledgeCommandService;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.knowledge.KnowledgeSearchCommand;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.model.SetDefaultModelCommand;
import io.github.yourname.agentstudio.model.TestModelCommand;
import io.github.yourname.agentstudio.model.UpdateModelStatusCommand;
import io.github.yourname.agentstudio.model.UpsertModelProfileCommand;
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
import io.github.yourname.agentstudio.orchestration.RunCommandService;
import io.github.yourname.agentstudio.orchestration.RunEventPublisher;
import io.github.yourname.agentstudio.orchestration.RunQueryService;
import io.github.yourname.agentstudio.security.CurrentActorProvider;
import io.github.yourname.agentstudio.skill.DiscoverRepositorySkillsCommand;
import io.github.yourname.agentstudio.skill.InstallSkillCommand;
import io.github.yourname.agentstudio.skill.SearchSkillRepositoriesCommand;
import io.github.yourname.agentstudio.skill.SkillCatalog;
import io.github.yourname.agentstudio.skill.SkillRepositoryService;
import io.github.yourname.agentstudio.skill.UpdateSkillCommand;
import io.github.yourname.agentstudio.tool.ToolCatalog;
import io.github.yourname.agentstudio.tool.WebSearchCommand;
import io.github.yourname.agentstudio.tool.WebSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
class AgentStudioController {

    private final CurrentActorProvider actors;
    private final ConversationService conversations;
    private final ModelCatalog models;
    private final AgentCatalog agents;
    private final ToolCatalog tools;
    private final WebSearchService webSearch;
    private final SkillCatalog skills;
    private final SkillRepositoryService skillRepositories;
    private final McpConnectionService mcpConnections;
    private final McpRepositoryService mcpRepositories;
    private final KnowledgeCommandService knowledgeCommands;
    private final KnowledgeQueryService knowledgeQueries;
    private final RunCommandService runCommands;
    private final RunQueryService runQueries;
    private final RunEventPublisher runEvents;

    AgentStudioController(
            CurrentActorProvider actors,
            ConversationService conversations,
            ModelCatalog models,
            AgentCatalog agents,
            ToolCatalog tools,
            WebSearchService webSearch,
            SkillCatalog skills,
            SkillRepositoryService skillRepositories,
            McpConnectionService mcpConnections,
            McpRepositoryService mcpRepositories,
            KnowledgeCommandService knowledgeCommands,
            KnowledgeQueryService knowledgeQueries,
            RunCommandService runCommands,
            RunQueryService runQueries,
            RunEventPublisher runEvents) {
        this.actors = actors;
        this.conversations = conversations;
        this.models = models;
        this.agents = agents;
        this.tools = tools;
        this.webSearch = webSearch;
        this.skills = skills;
        this.skillRepositories = skillRepositories;
        this.mcpConnections = mcpConnections;
        this.mcpRepositories = mcpRepositories;
        this.knowledgeCommands = knowledgeCommands;
        this.knowledgeQueries = knowledgeQueries;
        this.runCommands = runCommands;
        this.runQueries = runQueries;
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

    @GetMapping("/models")
    Object listModels() {
        return models.list();
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

    @GetMapping("/tools")
    Object listTools() {
        return Stream.concat(tools.list().stream(), mcpConnections.enabledRegisteredTools().stream()).toList();
    }

    @PostMapping("/web-search")
    Object searchWeb(@Valid @RequestBody WebSearchCommand command) {
        return webSearch.search(command);
    }

    @GetMapping("/skills")
    Object listSkills() {
        return skills.list();
    }

    @GetMapping("/skills/{id}")
    Object getSkill(@PathVariable String id) {
        return skills.get(id);
    }

    @PostMapping("/skills/install")
    @ResponseStatus(HttpStatus.CREATED)
    Object installSkill(@Valid @RequestBody InstallSkillCommand command) {
        return skills.install(command);
    }

    @PatchMapping("/skills/{id}")
    Object updateSkill(@PathVariable String id, @Valid @RequestBody UpdateSkillCommand command) {
        return skills.setEnabled(id, command);
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
            @RequestBody(required = false) CallMcpToolCommand command) {
        return mcpConnections.callTool(id, toolName, command);
    }

    @DeleteMapping("/mcp-connections/{id}/tools/{toolName}")
    ResponseEntity<Void> deleteMcpTool(@PathVariable String id, @PathVariable String toolName) {
        mcpConnections.deleteTool(id, toolName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/knowledge-bases")
    @ResponseStatus(HttpStatus.CREATED)
    Object createKnowledgeBase(@Valid @RequestBody CreateKnowledgeBaseCommand command, HttpServletRequest request) {
        return knowledgeCommands.create(command, actors.current(request));
    }

    @GetMapping("/knowledge-bases")
    Object listKnowledgeBases(HttpServletRequest request) {
        return knowledgeQueries.list(actors.current(request));
    }

    @PostMapping("/knowledge-bases/{id}/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object ingestDocument(@PathVariable String id, @Valid @RequestBody IngestDocumentCommand command, HttpServletRequest request) {
        return knowledgeCommands.ingest(id, command, actors.current(request));
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

    @GetMapping("/runs/{id}")
    Object getRun(@PathVariable String id, HttpServletRequest request) {
        return runQueries.get(id, actors.current(request));
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
