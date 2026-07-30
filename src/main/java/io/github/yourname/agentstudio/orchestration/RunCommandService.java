package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.agent.AgentCatalog;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.conversation.ConversationService;
import io.github.yourname.agentstudio.conversation.MessageRole;
import io.github.yourname.agentstudio.knowledge.EvidenceBundle;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.knowledge.KnowledgeSearchCommand;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates durable runs and executes them asynchronously.
 *
 * <p>The HTTP request only creates the run. Model work happens after the 202
 * response, which is what allows page refresh, SSE reconnect, and later worker
 * replacement without changing the public API.
 */
@Service
public class RunCommandService {

    private final AppProperties properties;
    private final AgentRunRepository runs;
    private final ConversationService conversations;
    private final AgentCatalog agents;
    private final KnowledgeQueryService knowledge;
    private final ModelGateway modelGateway;
    private final RunEventPublisher events;

    public RunCommandService(
            AppProperties properties,
            AgentRunRepository runs,
            ConversationService conversations,
            AgentCatalog agents,
            KnowledgeQueryService knowledge,
            ModelGateway modelGateway,
            RunEventPublisher events) {
        this.properties = properties;
        this.runs = runs;
        this.conversations = conversations;
        this.agents = agents;
        this.knowledge = knowledge;
        this.modelGateway = modelGateway;
        this.events = events;
    }

    @Transactional
    public CreateRunResponse create(CreateRunCommand command, ActorContext actor) {
        conversations.append(command.conversationId(), MessageRole.USER, command.text(), null, actor);
        String agentId = blankToDefault(command.agentId(), "default-assistant");
        String modelId = blankToDefault(command.modelProfileId(), properties.ai().defaultModelProfileId());
        var run = runs.save(new AgentRunEntity(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                actor.userId(),
                command.conversationId(),
                modelId,
                agentId,
                Instant.now()));

        CompletableFuture.runAsync(() -> execute(run.id(), command, actor));
        return new CreateRunResponse(run.id(), RunStatus.CREATED, "/api/v1/runs/" + run.id() + "/events");
    }

    private void execute(String runId, CreateRunCommand command, ActorContext actor) {
        try {
            AgentRunEntity run = runs.findByIdAndTenantId(runId, actor.tenantId()).orElseThrow();
            run.start();
            runs.save(run);
            events.publish(runId, RunEventType.RUN_STARTED, "Run accepted by local coordinator.", actor);
            events.publish(runId, RunEventType.STEP_STARTED, "single-agent", actor);

            var agent = agents.get(run.agentId());
            EvidenceBundle evidence = knowledge.search(new KnowledgeSearchCommand(command.knowledgeBaseIds(), command.text(), 5), actor);
            events.publish(runId, RunEventType.RETRIEVAL_COMPLETED, "evidence=" + evidence.evidence().size(), actor);

            List<ModelGateway.ModelMessage> messages = new ArrayList<>();
            messages.add(new ModelGateway.ModelMessage("system", buildSystemPrompt(agent.systemPrompt(), evidence)));
            conversations.history(run.conversationId(), actor).forEach(message ->
                    messages.add(new ModelGateway.ModelMessage(message.role().name().toLowerCase(), message.content())));

            var answer = modelGateway.complete(new ModelGateway.ModelCompletionRequest(run.modelProfileId(), messages));
            for (String part : tokenBatches(answer.content())) {
                events.publish(runId, RunEventType.TOKEN_DELTA, part, actor);
            }

            conversations.append(run.conversationId(), MessageRole.ASSISTANT, answer.content(), runId, actor);
            run.succeed(answer.content());
            runs.save(run);
            events.publish(runId, RunEventType.STEP_COMPLETED, "single-agent", actor);
            events.publish(runId, RunEventType.FINAL_ANSWER, answer.content(), actor);
        } catch (Exception ex) {
            runs.findByIdAndTenantId(runId, actor.tenantId()).ifPresent(run -> {
                run.fail(ex.getMessage());
                runs.save(run);
            });
            events.publish(runId, RunEventType.RUN_FAILED, ex.getMessage(), actor);
        }
    }

    private static String buildSystemPrompt(String agentPrompt, EvidenceBundle evidence) {
        if (evidence.isEmpty()) {
            return agentPrompt;
        }
        StringBuilder builder = new StringBuilder(agentPrompt).append("\n\nRetrieved evidence:\n");
        for (EvidenceBundle.Evidence item : evidence.evidence()) {
            builder.append("- [")
                    .append(item.sourceName()).append("#").append(item.chunkIndex())
                    .append("] ").append(item.quote()).append('\n');
        }
        return builder.toString();
    }

    private static List<String> tokenBatches(String content) {
        if (content == null || content.isBlank()) {
            return List.of("");
        }
        List<String> result = new ArrayList<>();
        for (int start = 0; start < content.length(); start += 80) {
            result.add(content.substring(start, Math.min(start + 80, content.length())));
        }
        return result;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
