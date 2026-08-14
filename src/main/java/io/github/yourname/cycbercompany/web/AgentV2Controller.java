package io.github.yourname.cycbercompany.web;

import io.github.yourname.cycbercompany.agent.AgentV2Service;
import io.github.yourname.cycbercompany.agent.AgentDraftTestCommand;
import io.github.yourname.cycbercompany.agent.AgentDraftTestService;
import io.github.yourname.cycbercompany.agent.AgentEvaluationService;
import io.github.yourname.cycbercompany.agent.CreateAgentV2Command;
import io.github.yourname.cycbercompany.agent.UpdateAgentManifestCommand;
import io.github.yourname.cycbercompany.agent.UpdateAgentSettingsCommand;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/agents")
class AgentV2Controller {

    private final CurrentActorProvider actors;
    private final AgentV2Service agents;
    private final AgentDraftTestService draftTests;
    private final AgentEvaluationService evaluations;

    AgentV2Controller(
            CurrentActorProvider actors,
            AgentV2Service agents,
            AgentDraftTestService draftTests,
            AgentEvaluationService evaluations) {
        this.actors = actors;
        this.agents = agents;
        this.draftTests = draftTests;
        this.evaluations = evaluations;
    }

    @GetMapping
    Object list(HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.list(actor.tenantId(), actor.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody CreateAgentV2Command command, HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.create(command, actor.tenantId(), actor.userId());
    }

    @GetMapping("/{agentId}")
    Object get(@PathVariable String agentId, HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.get(agentId, actor.tenantId(), actor.userId());
    }

    @PatchMapping("/{agentId}")
    Object updateSettings(
            @PathVariable String agentId,
            @Valid @RequestBody UpdateAgentSettingsCommand command,
            HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.updateSettings(agentId, command, actor.tenantId(), actor.userId());
    }

    @GetMapping("/{agentId}/versions")
    Object versions(@PathVariable String agentId, HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.versions(agentId, actor.tenantId(), actor.userId());
    }

    @GetMapping("/{agentId}/versions/{versionId}")
    Object version(
            @PathVariable String agentId,
            @PathVariable String versionId,
            HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.version(agentId, versionId, actor.tenantId(), actor.userId());
    }

    @PostMapping("/{agentId}/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    Object createDraft(@PathVariable String agentId, HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.createDraft(agentId, actor.tenantId(), actor.userId());
    }

    @PutMapping("/{agentId}/drafts/{versionId}/manifest")
    Object updateDraft(
            @PathVariable String agentId,
            @PathVariable String versionId,
            @Valid @RequestBody UpdateAgentManifestCommand command,
            HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.updateDraft(agentId, versionId, command, actor.tenantId(), actor.userId());
    }

    @PostMapping("/{agentId}/drafts/{versionId}/validate")
    Object validateDraft(
            @PathVariable String agentId,
            @PathVariable String versionId,
            HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.validateDraft(agentId, versionId, actor.tenantId(), actor.userId());
    }

    @PostMapping("/{agentId}/drafts/{versionId}/test-runs")
    Object testDraft(
            @PathVariable String agentId,
            @PathVariable String versionId,
            @Valid @RequestBody AgentDraftTestCommand command,
            HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return draftTests.test(agentId, versionId, command, actor.tenantId(), actor.userId());
    }

    @PostMapping("/{agentId}/drafts/{versionId}/evaluations")
    Object evaluateDraft(
            @PathVariable String agentId,
            @PathVariable String versionId,
            HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return evaluations.evaluate(agentId, versionId, actor.tenantId(), actor.userId());
    }

    @PostMapping("/{agentId}/drafts/{versionId}/publish")
    Object publish(
            @PathVariable String agentId,
            @PathVariable String versionId,
            HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.publish(agentId, versionId, actor.tenantId(), actor.userId());
    }

    @PostMapping("/{agentId}/archive")
    Object archive(@PathVariable String agentId, HttpServletRequest request) {
        ActorContext actor = actors.current(request);
        return agents.archive(agentId, actor.tenantId(), actor.userId());
    }
}
