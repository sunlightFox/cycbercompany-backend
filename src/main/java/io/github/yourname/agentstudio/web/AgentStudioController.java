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
import io.github.yourname.agentstudio.model.UpsertModelProfileCommand;
import io.github.yourname.agentstudio.orchestration.CreateRunCommand;
import io.github.yourname.agentstudio.orchestration.RunCommandService;
import io.github.yourname.agentstudio.orchestration.RunEventPublisher;
import io.github.yourname.agentstudio.orchestration.RunQueryService;
import io.github.yourname.agentstudio.security.CurrentActorProvider;
import io.github.yourname.agentstudio.tool.ToolCatalog;
import io.github.yourname.agentstudio.tool.WebSearchCommand;
import io.github.yourname.agentstudio.tool.WebSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    Object saveModel(@Valid @RequestBody UpsertModelProfileCommand command) {
        return models.save(command);
    }

    @GetMapping("/agents")
    Object listAgents() {
        return agents.list();
    }

    @GetMapping("/tools")
    Object listTools() {
        return tools.list();
    }

    @PostMapping("/web-search")
    Object searchWeb(@Valid @RequestBody WebSearchCommand command) {
        return webSearch.search(command);
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
