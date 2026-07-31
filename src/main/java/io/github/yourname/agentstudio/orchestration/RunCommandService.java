package io.github.yourname.agentstudio.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.agent.AgentCatalog;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.conversation.ConversationService;
import io.github.yourname.agentstudio.conversation.MessageRole;
import io.github.yourname.agentstudio.knowledge.EvidenceBundle;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.knowledge.KnowledgeSearchCommand;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.mcp.McpConnectionService;
import io.github.yourname.agentstudio.mcp.McpToolCallResult;
import io.github.yourname.agentstudio.node.NodeToolApprovalDecisionView;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.WebSearchCommand;
import io.github.yourname.agentstudio.tool.WebSearchResult;
import io.github.yourname.agentstudio.tool.WebSearchService;
import io.github.yourname.agentstudio.tool.CodingWorkspaceScope;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates durable runs and executes them asynchronously.
 *
 * <p>The HTTP request only creates the run. Model work happens after the 202
 * response, which is what allows page refresh, SSE reconnect, and later worker
 * replacement without changing the public API.
 */
@Service
public class RunCommandService {

    /**
     * Matches compact technical/product tokens that users often place inside a
     * Chinese request, such as "assistant-ui", "GitHub", "Next.js", or a URL.
     */
    private static final Pattern LATIN_SEARCH_TOKEN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#@+\\-]{1,}");
    private static final Pattern TOOL_CALL_BLOCK =
            Pattern.compile("(?is)<tool_call>.*?</tool_call>");
    private static final Pattern TOOL_RESULT_BLOCK =
            Pattern.compile("(?is)<tool_result>.*?</tool_result>");
    private static final DateTimeFormatter SERVER_TIME_FORMAT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final AppProperties properties;
    private final AgentRunRepository runs;
    private final CodingRunContinuationRepository continuations;
    private final ConversationService conversations;
    private final AgentCatalog agents;
    private final KnowledgeQueryService knowledge;
    private final WebSearchService webSearch;
    private final McpConnectionService mcpConnections;
    private final ModelCatalog models;
    private final ModelGateway modelGateway;
    private final CodingAgentLoop codingAgentLoop;
    private final NodeService nodes;
    private final RunExecutionRegistry executions;
    private final RunEventPublisher events;
    private final ObjectMapper objectMapper;

    public RunCommandService(
            AppProperties properties,
            AgentRunRepository runs,
            CodingRunContinuationRepository continuations,
            ConversationService conversations,
            AgentCatalog agents,
            KnowledgeQueryService knowledge,
            WebSearchService webSearch,
            McpConnectionService mcpConnections,
            ModelCatalog models,
            ModelGateway modelGateway,
            CodingAgentLoop codingAgentLoop,
            NodeService nodes,
            RunExecutionRegistry executions,
            RunEventPublisher events,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.runs = runs;
        this.continuations = continuations;
        this.conversations = conversations;
        this.agents = agents;
        this.knowledge = knowledge;
        this.webSearch = webSearch;
        this.mcpConnections = mcpConnections;
        this.models = models;
        this.modelGateway = modelGateway;
        this.codingAgentLoop = codingAgentLoop;
        this.nodes = nodes;
        this.executions = executions;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreateRunResponse create(CreateRunCommand command, ActorContext actor) {
        CodingWorkspaceScope.from(command.workingDirectory());
        conversations.append(command.conversationId(), MessageRole.USER, command.text(), null, actor);
        String agentId = blankToDefault(command.agentId(), "default-assistant");
        String modelId = blankToDefault(command.modelProfileId(), models.defaultModelProfileId());
        var run = runs.save(new AgentRunEntity(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                actor.userId(),
                command.conversationId(),
                modelId,
                agentId,
                Instant.now()));

        // The worker reads the run back from the database. Schedule it only after this
        // transaction commits; otherwise a fast worker can observe no run yet.
        scheduleAfterCommit(() -> executions.submit(run.id(), () -> execute(run.id(), command, actor)));
        return new CreateRunResponse(run.id(), RunStatus.CREATED, "/api/v1/runs/" + run.id() + "/events");
    }

    @Transactional
    public RunView cancel(String runId, ActorContext actor) {
        AgentRunEntity run = runs.findByIdAndTenantId(runId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        run.cancel();
        continuations.findByRunIdAndTenantId(runId, actor.tenantId()).ifPresent(continuations::delete);
        runs.save(run);
        executions.cancel(runId);
        codingAgentLoop.cleanupManagedProcesses(runId, actor);
        events.publish(runId, RunEventType.RUN_CANCELLED, "Run cancelled by user.", actor);
        return RunView.from(run);
    }

    private void execute(String runId, CreateRunCommand command, ActorContext actor) {
        try {
            CodingWorkspaceScope workspaceScope = CodingWorkspaceScope.from(command.workingDirectory());
            AgentRunEntity run = runs.findByIdAndTenantId(runId, actor.tenantId()).orElseThrow();
            run.start();
            runs.save(run);
            events.publish(runId, RunEventType.RUN_STARTED, "Run accepted by local coordinator.", actor);
            events.publish(runId, RunEventType.STEP_STARTED, "single-agent", actor);

            var agent = agents.get(run.agentId());
            EvidenceBundle evidence = knowledge.search(
                    new KnowledgeSearchCommand(command.knowledgeBaseIds(), command.text(), 5),
                    actor);

            String webQuery = webSearchQuery(command.text());
            String webRetrievalNote = "";
            List<WebSearchResult> webResults = List.of();
            List<McpToolCallResult> mcpResults = List.of();
            if (shouldSearchWeb(command)) {
                try {
                    webResults = webSearch.search(new WebSearchCommand(webQuery, 5));
                } catch (Exception searchFailure) {
                    webRetrievalNote = "Web search was requested but failed: " + safeErrorMessage(searchFailure);
                }
            }
            if (shouldUseSelectedMcpSearch(command)) {
                mcpResults = mcpConnections.callLikelySearchTools(command.mcpServerIds(), webQuery.isBlank() ? command.text() : webQuery);
            }
            events.publish(
                    runId,
                    RunEventType.RETRIEVAL_COMPLETED,
                    "knowledge=" + evidence.evidence().size()
                            + ", web=" + webResults.size()
                            + ", mcp=" + mcpResults.size()
                            + (webResults.isEmpty() && webRetrievalNote.isBlank() ? "" : ", query=" + webQuery)
                            + (webRetrievalNote.isBlank() ? "" : ", note=" + webRetrievalNote),
                    actor);

            List<ModelGateway.ModelMessage> messages = new ArrayList<>();
            messages.add(new ModelGateway.ModelMessage(
                    "system",
                    buildSystemPrompt(agent.systemPrompt(), command, evidence, webResults, mcpResults, webQuery, webRetrievalNote)));
            conversations.history(run.conversationId(), actor).forEach(message ->
                    messages.add(new ModelGateway.ModelMessage(message.role().name().toLowerCase(), message.content())));

            String answerContent;
            if (command.nodeId() != null && !command.nodeId().isBlank()) {
                events.publish(runId, RunEventType.STEP_STARTED, "coding-agent", actor);
                answerContent = sanitizeModelOutput(codingAgentLoop.execute(
                        runId,
                        run.modelProfileId(),
                        command.nodeId(),
                        messages,
                        actor,
                        workspaceScope));
                events.publish(runId, RunEventType.STEP_COMPLETED, "coding-agent", actor);
            } else {
                var answer = modelGateway.complete(new ModelGateway.ModelCompletionRequest(run.modelProfileId(), messages));
                answerContent = sanitizeModelOutput(answer.content());
            }
            for (String part : tokenBatches(answerContent)) {
                events.publish(runId, RunEventType.TOKEN_DELTA, part, actor);
            }

            conversations.append(run.conversationId(), MessageRole.ASSISTANT, answerContent, runId, actor);
            run.succeed(answerContent);
            runs.save(run);
            events.publish(runId, RunEventType.STEP_COMPLETED, "single-agent", actor);
            events.publish(runId, RunEventType.FINAL_ANSWER, answerContent, actor);
        } catch (CodingApprovalRequiredException approvalRequired) {
            suspendForApproval(runId, command.nodeId(), command.workingDirectory(), approvalRequired, actor);
        } catch (Exception ex) {
            failUnlessCancelled(runId, ex, actor);
        }
    }

    /**
     * Starts the persisted continuation after a node tool is approved or rejected. A rejected
     * request is also resumed: the model receives a structured rejection and can choose a safer
     * alternative instead of leaving the run stranded.
     */
    @Transactional
    public void resumeAfterToolApproval(NodeToolApprovalDecisionView decision, ActorContext actor) {
        if (decision == null || decision.approval() == null || decision.approval().runId() == null
                || decision.approval().runId().isBlank()) {
            return;
        }

        var approval = decision.approval();
        var continuation = continuations.findByRunIdAndTenantId(approval.runId(), actor.tenantId()).orElse(null);
        if (continuation == null
                || !continuation.approvalId().equals(approval.id())
                || !continuation.toolCallId().equals(approval.toolCallId())) {
            return;
        }
        var run = runs.findByIdAndTenantId(approval.runId(), actor.tenantId()).orElse(null);
        if (run == null || run.status() != RunStatus.WAITING_APPROVAL) {
            return;
        }

        List<ModelGateway.ModelMessage> messages = deserializeMessages(continuation.messagesJson());
        messages.add(ModelGateway.ModelMessage.toolResult(approval.toolCallId(), approvalResult(decision)));
        continuations.delete(continuation);
        run.resume();
        runs.save(run);
        events.publish(run.id(), RunEventType.RUN_RESUMED, "approvalId=" + approval.id(), actor);
        scheduleAfterCommit(() -> executions.submit(run.id(), () -> executeResumedCoding(
                run.id(),
                continuation.nodeId(),
                continuation.workingDirectory(),
                messages,
                actor)));
    }

    private void executeResumedCoding(
            String runId,
            String nodeId,
            String workingDirectory,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor) {
        try {
            AgentRunEntity run = runs.findByIdAndTenantId(runId, actor.tenantId()).orElseThrow();
            if (run.status() != RunStatus.RUNNING) {
                return;
            }
            events.publish(runId, RunEventType.STEP_STARTED, "coding-agent resumed", actor);
            String answer = sanitizeModelOutput(codingAgentLoop.resume(
                    runId,
                    run.modelProfileId(),
                    nodeId,
                    messages,
                    actor,
                    CodingWorkspaceScope.from(workingDirectory)));
            events.publish(runId, RunEventType.STEP_COMPLETED, "coding-agent resumed", actor);
            for (String part : tokenBatches(answer)) {
                events.publish(runId, RunEventType.TOKEN_DELTA, part, actor);
            }
            conversations.append(run.conversationId(), MessageRole.ASSISTANT, answer, runId, actor);
            run.succeed(answer);
            runs.save(run);
            events.publish(runId, RunEventType.STEP_COMPLETED, "single-agent", actor);
            events.publish(runId, RunEventType.FINAL_ANSWER, answer, actor);
        } catch (CodingApprovalRequiredException approvalRequired) {
            suspendForApproval(runId, nodeId, workingDirectory, approvalRequired, actor);
        } catch (Exception ex) {
            failUnlessCancelled(runId, ex, actor);
        }
    }

    private void suspendForApproval(
            String runId,
            String nodeId,
            String workingDirectory,
            CodingApprovalRequiredException approvalRequired,
            ActorContext actor) {
        AgentRunEntity run = runs.findByIdAndTenantId(runId, actor.tenantId()).orElseThrow();
        if (run.status() != RunStatus.RUNNING) {
            throw new IllegalStateException("Cannot suspend a coding run that is not running: " + runId);
        }
        continuations.save(new CodingRunContinuationEntity(
                runId,
                actor.tenantId(),
                nodeId,
                CodingWorkspaceScope.from(workingDirectory).relativePath(),
                approvalRequired.approvalId(),
                approvalRequired.toolCallId(),
                serializeMessages(approvalRequired.messages()),
                Instant.now()));
        run.waitForApproval();
        runs.save(run);
        events.publish(
                runId,
                RunEventType.RUN_WAITING_APPROVAL,
                "approvalId=" + approvalRequired.approvalId(),
                actor);
    }

    private void failUnlessCancelled(String runId, Exception ex, ActorContext actor) {
        boolean cancelled = runs.findByIdAndTenantId(runId, actor.tenantId()).map(run -> {
            if (run.status() == RunStatus.CANCELLED) {
                return true;
            }
            run.fail(ex.getMessage());
            runs.save(run);
            return false;
        }).orElse(false);
        if (!cancelled) {
            events.publish(runId, RunEventType.RUN_FAILED, ex.getMessage(), actor);
        }
    }

    private String approvalResult(NodeToolApprovalDecisionView decision) {
        var result = new LinkedHashMap<String, Object>();
        result.put("tool", decision.approval().toolName());
        if (decision.execution() == null) {
            result.put("status", "REJECTED");
            result.put("error", "The user rejected this tool call.");
        } else {
            result.put("status", decision.execution().status());
            result.put("result", decision.execution().result() == null ? Map.of() : decision.execution().result());
            result.put("error", decision.execution().errorMessage() == null ? "" : decision.execution().errorMessage());
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize approved node tool result.", ex);
        }
    }

    private String serializeMessages(List<ModelGateway.ModelMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to persist coding continuation.", ex);
        }
    }

    private List<ModelGateway.ModelMessage> deserializeMessages(String messagesJson) {
        try {
            return new ArrayList<>(objectMapper.readValue(
                    messagesJson,
                    new TypeReference<List<ModelGateway.ModelMessage>>() {
                    }));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to restore coding continuation.", ex);
        }
    }

    private static void scheduleAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            CompletableFuture.runAsync(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(task);
            }
        });
    }

    static String buildSystemPrompt(
            String agentPrompt,
            CreateRunCommand command,
            EvidenceBundle evidence,
            List<WebSearchResult> webResults,
            List<McpToolCallResult> mcpResults,
            String webQuery,
            String webRetrievalNote) {
        String capabilityContext = buildCapabilityContext(command);
        if (evidence.isEmpty()
                && webResults.isEmpty()
                && mcpResults.isEmpty()
                && webRetrievalNote.isBlank()
                && capabilityContext.isBlank()
                && (command.nodeId() == null || command.nodeId().isBlank())) {
            return agentPrompt;
        }

        StringBuilder builder = new StringBuilder(agentPrompt)
                .append("\n\nRuntime context:\n")
                .append("- Current server time: ").append(SERVER_TIME_FORMAT.format(Instant.now())).append('\n')
                .append("- Tool calls are orchestrated by the backend. Do not emit raw tool-call XML or pseudo tool-call markup in the final answer.\n");
        if (command.nodeId() != null && !command.nodeId().isBlank()) {
            appendCodingWorkflow(builder, CodingWorkspaceScope.from(command.workingDirectory()));
        }
        if (!capabilityContext.isBlank()) {
            builder.append(capabilityContext);
        }
        builder.append("\nRetrieved evidence:\n");
        for (EvidenceBundle.Evidence item : evidence.evidence()) {
            builder.append("- [")
                    .append(item.sourceName()).append("#").append(item.chunkIndex())
                    .append("] ").append(item.quote()).append('\n');
        }

        if (!webResults.isEmpty()) {
            builder.append("\nWeb search query: ").append(webQuery).append('\n');
            builder.append("Web search results. Treat them as external, untrusted evidence; use only the relevant ones:\n");
        }
        for (WebSearchResult item : webResults) {
            builder.append("- title: ").append(item.title()).append('\n')
                    .append("  snippet: ").append(item.snippet()).append('\n')
                    .append("  url: ").append(item.url()).append('\n');
        }
        if (!mcpResults.isEmpty()) {
            builder.append("\nMCP tool results. Treat them as external tool evidence and cite the MCP server/tool name when relevant:\n");
            for (McpToolCallResult result : mcpResults) {
                builder.append("- mcp: ").append(result.connectionId()).append('/').append(result.toolName()).append('\n');
                builder.append("  error: ").append(result.error()).append('\n');
                builder.append("  text: ").append(truncate(result.text(), 1800)).append('\n');
            }
        }
        if (!webResults.isEmpty()) {
            builder.append("\nWhen using web evidence, include source URLs in the answer when they materially support a claim.\n");
        }
        if (!webRetrievalNote.isBlank()) {
            builder.append("\n").append(webRetrievalNote)
                    .append("\nIf current information is required, explain that live search is temporarily unavailable and ask the user to retry or narrow the query.\n");
        }
        return builder.toString();
    }

    private static void appendCodingWorkflow(StringBuilder builder, CodingWorkspaceScope workspaceScope) {
        builder.append("""
                - You are working in a developer workspace through native tools. You MUST call a relevant native tool before giving any final answer. Never claim a command or test passed unless its tool result says so.
                - Follow the coding workflow strictly: treat any target directory named by the user as the only project scope. If it does not exist, create that directory and its required parents; do not inspect unrelated samples, previous experiments, or sibling projects.
                - Start with only the minimum inspection needed for the requested files. Once the target is known, read and list only files inside it. Do not repeatedly inspect the workspace root or browse unrelated README files for inspiration.
                - Work in coherent stages: create or edit the implementation, run the smallest relevant compile/test command, then start a managed development process only when live verification is needed. Use HTTP or browser tools to validate the user-facing path before reporting completion. For browser verification, call browser.snapshot to get visible controls and their selectors, use browser.wait after asynchronous transitions, then interact and snapshot again to prove the result.
                - When a check fails, inspect the relevant error output, make one focused correction, and repeat that check. Prefer direct file writes for new files and focused patches for changes. Keep tool calls purposeful because each coding run has a finite tool budget.
                - In the final answer, state the files changed, the concrete verification performed, any process URL that remains running, and any limitation that was not verified.
                """);
        builder.append("- Project scope for this run: ")
                .append(workspaceScope.isRoot() ? "the node workspace root" : workspaceScope.relativePath())
                .append(". All file paths and working directories must be relative to this scope.\n");
    }

    private static boolean shouldSearchWeb(CreateRunCommand command) {
        boolean requestScopedCapabilities = command.toolNames() != null || command.skillIds() != null;
        if (requestScopedCapabilities
                && !isCapabilitySelected(command.toolNames(), "web_search")
                && !isCapabilitySelected(command.skillIds(), "web-research")) {
            return false;
        }
        String text = command.text();
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return normalized.contains("\u8054\u7f51")
                || normalized.contains("\u641c\u7d22")
                || normalized.contains("\u67e5\u4e00\u4e0b")
                || normalized.contains("\u7f51\u4e0a")
                || normalized.contains("\u6700\u65b0")
                || normalized.contains("\u4eca\u5929")
                || normalized.contains("\u65b0\u95fb")
                || normalized.contains("\u8d44\u8baf")
                || normalized.contains("\u4ef7\u683c")
                || normalized.contains("\u5b98\u7f51")
                || normalized.contains("github")
                || normalized.contains("current")
                || normalized.contains("latest")
                || normalized.contains("today")
                || normalized.contains("news")
                || normalized.contains("search");
    }

    private static boolean shouldUseSelectedMcpSearch(CreateRunCommand command) {
        return command.mcpServerIds() != null
                && !command.mcpServerIds().isEmpty()
                && shouldSearchWeb(command);
    }

    private static String buildCapabilityContext(CreateRunCommand command) {
        StringBuilder builder = new StringBuilder();
        appendCapabilityLine(builder, "Selected skill IDs", command.skillIds());
        appendCapabilityLine(builder, "Selected MCP server IDs", command.mcpServerIds());
        appendCapabilityLine(builder, "Selected tool names", command.toolNames());
        if (!builder.isEmpty()) {
            builder.append("- Use only selected tools for backend-orchestrated retrieval. If a selected MCP server is not connected yet, say it is selected in the workspace but has no live executor attached.\n");
        }
        return builder.toString();
    }

    private static void appendCapabilityLine(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("- ").append(label).append(": ").append(String.join(", ", values)).append('\n');
    }

    private static boolean isCapabilitySelected(List<String> selected, String capabilityId) {
        return selected != null && selected.contains(capabilityId);
    }

    /**
     * Converts an instruction-like user message into a compact search query.
     *
     * <p>Search engines perform better with the subject than with the whole
     * instruction. For example, "search assistant-ui GitHub and cite sources"
     * should search for "assistant-ui GitHub".
     */
    private static String webSearchQuery(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder latinTokens = new StringBuilder();
        var matcher = LATIN_SEARCH_TOKEN.matcher(text);
        while (matcher.find()) {
            if (!latinTokens.isEmpty()) {
                latinTokens.append(' ');
            }
            latinTokens.append(matcher.group());
        }
        if (!latinTokens.isEmpty()) {
            return latinTokens.toString();
        }

        return text
                .replaceAll("(?i)\\b(search|please|find|current|latest|today|news|source|sources|link|links)\\b", " ")
                .replace("\u641c\u7d22\u4e00\u4e0b", " ")
                .replace("\u641c\u7d22", " ")
                .replace("\u8054\u7f51", " ")
                .replace("\u67e5\u4e00\u4e0b", " ")
                .replace("\u662f\u4ec0\u4e48", " ")
                .replace("\u56de\u7b54\u65f6", " ")
                .replace("\u5e26\u6765\u6e90\u94fe\u63a5", " ")
                .replace("\u5e26\u94fe\u63a5", " ")
                .replace("\u8bf7", " ")
                .replace("\u5e2e\u6211", " ")
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) + "..." : message;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private static String sanitizeModelOutput(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return TOOL_RESULT_BLOCK.matcher(TOOL_CALL_BLOCK.matcher(content).replaceAll(""))
                .replaceAll("")
                .replaceAll("(?m)^\\s*\\]<\\]minimax\\[>.*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
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
