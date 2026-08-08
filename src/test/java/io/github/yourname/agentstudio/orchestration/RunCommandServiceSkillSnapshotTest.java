package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.agent.AgentCatalog;
import io.github.yourname.agentstudio.agent.AgentCollaboratorRuntimeDefinition;
import io.github.yourname.agentstudio.agent.AgentDefinitionView;
import io.github.yourname.agentstudio.agent.AgentRuntimeDefinition;
import io.github.yourname.agentstudio.agent.AgentRuntimeDefinitionService;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.conversation.ConversationAttachmentService;
import io.github.yourname.agentstudio.conversation.ConversationService;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelCapability;
import io.github.yourname.agentstudio.model.ModelProfileView;
import io.github.yourname.agentstudio.model.ProviderType;
import io.github.yourname.agentstudio.memory.MemoryRetrievalService;
import io.github.yourname.agentstudio.memory.MemoryCandidateService;
import io.github.yourname.agentstudio.memory.MemorySnapshot;
import io.github.yourname.agentstudio.memory.MemoryType;
import io.github.yourname.agentstudio.persona.UserPersonaContext;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.node.NodeConnectionView;
import io.github.yourname.agentstudio.node.NodeDetailView;
import io.github.yourname.agentstudio.node.NodeKind;
import io.github.yourname.agentstudio.node.NodeStatus;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.skill.SkillCatalog;
import io.github.yourname.agentstudio.skill.SkillRunBinding;
import io.github.yourname.agentstudio.skill.SkillAnalyzer;
import io.github.yourname.agentstudio.skill.SkillCompatibilityService;
import io.github.yourname.agentstudio.skill.CompatibilityReport;
import io.github.yourname.agentstudio.tool.ToolRouter;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.tool.ToolProviderResult;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunCommandServiceSkillSnapshotTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-1", "user-1", Set.of("USER"), Set.of("agent:run"));

    private AgentRunRepository runs;
    private CodingRunContinuationRepository continuations;
    private ConversationService conversations;
    private KnowledgeQueryService knowledge;
    private AgentCatalog agents;
    private SkillCatalog skills;
    private SkillAnalyzer skillAnalyzer;
    private SkillCompatibilityService skillCompatibility;
    private ModelGateway modelGateway;
    private ModelCatalog models;
    private ToolRouter toolRouter;
    private CodingAgentLoop codingAgentLoop;
    private NodeService nodes;
    private RunExecutionRegistry executions;
    private ConversationRunQueue queue;
    private RunEventPublisher events;
    private RunCommandService service;
    private Runnable queuedWorker;

    @BeforeEach
    void setUp() {
        runs = mock(AgentRunRepository.class);
        continuations = mock(CodingRunContinuationRepository.class);
        conversations = mock(ConversationService.class);
        knowledge = mock(KnowledgeQueryService.class);
        agents = mock(AgentCatalog.class);
        skills = mock(SkillCatalog.class);
        skillAnalyzer = mock(SkillAnalyzer.class);
        skillCompatibility = mock(SkillCompatibilityService.class);
        modelGateway = mock(ModelGateway.class);
        models = mock(ModelCatalog.class);
        toolRouter = mock(ToolRouter.class);
        codingAgentLoop = mock(CodingAgentLoop.class);
        nodes = mock(NodeService.class);
        executions = mock(RunExecutionRegistry.class);
        queue = mock(ConversationRunQueue.class);
        events = mock(RunEventPublisher.class);

        when(agents.get("agent-1")).thenReturn(new AgentDefinitionView(
                "agent-1", "Agent", "", "Agent system instruction", "model-1", "local_time", true));
        when(models.get("model-1")).thenReturn(new ModelProfileView(
                "model-1", ProviderType.OPENAI_COMPATIBLE, "https://example.test/v1", "test-model",
                "TEST_KEY", true, "***", Set.of(ModelCapability.TEXT, ModelCapability.TOOLS), true, false));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of());
        when(knowledge.resolveKnowledgeBaseIds(any(), any())).thenReturn(List.of());
        when(skillAnalyzer.analyze(any())).thenReturn(List.of());
        when(skillCompatibility.check(any(), any(), any())).thenReturn(
                new CompatibilityReport(true, List.of(), List.of(), List.of(), List.of()));

        when(runs.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queue.reserve(any(), anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            queuedWorker = invocation.getArgument(2);
            return 1;
        });
        // 测试中同步运行 worker，避免线程调度让“Skill 是否进入模型请求”的断言产生竞态。
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(executions).submit(anyString(), any(Runnable.class));

        service = new RunCommandService(
                mock(AppProperties.class),
                runs,
                continuations,
                conversations,
                mock(ConversationAttachmentService.class),
                knowledge,
                agents,
                skills,
                skillAnalyzer,
                skillCompatibility,
                models,
                modelGateway,
                codingAgentLoop,
                toolRouter,
                nodes,
                executions,
                queue,
                events,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void createPersistsImmutableSkillBindingsAndTheirSnapshotDigest() {
        SkillRunBinding binding = new SkillRunBinding(
                "review-skill",
                "Review Skill",
                "Review code carefully",
                "sha256:" + "a".repeat(64),
                "example/skills",
                "https://github.com/example/skills",
                "main",
                "b".repeat(40),
                "review");
        when(skills.resolveForRun(List.of("review-skill"))).thenReturn(List.of(binding));
        when(skills.compileInstructions(List.of(binding))).thenReturn("compiled instruction");

        CreateRunResponse response = service.create(command(List.of("review-skill")), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        assertThat(response.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(persisted.skillBindingsJson())
                .contains("review-skill")
                .contains(binding.digest())
                .contains(binding.resolvedCommit());
        assertThat(persisted.skillSnapshotDigest())
                .startsWith("sha256:")
                .hasSize(71);
        assertThat(RunView.from(persisted).skillSnapshotDigest()).isEqualTo(persisted.skillSnapshotDigest());
        assertThat(persisted.runSpecDigest()).startsWith("sha256:").hasSize(71);
        assertThat(persisted.runSpecJson()).contains("review-skill").contains("toolBindings");
        verify(skills).compileInstructions(List.of(binding));
        verify(events).publish(
                persisted.id(),
                RunEventType.SKILLS_RESOLVED,
                "count=1, snapshot=" + persisted.skillSnapshotDigest(),
                ACTOR);
    }

    @Test
    void createCapturesResolvedTenantKnowledgeBasesInTheImmutableRunSpec() {
        when(knowledge.resolveKnowledgeBaseIds(List.of(), ACTOR)).thenReturn(List.of("kb-recent", "kb-resume"));
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");

        service.create(command(List.of()), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        assertThat(runCaptor.getValue().runSpecJson())
                .contains("kb-recent")
                .contains("kb-resume");
        verify(knowledge).resolveKnowledgeBaseIds(List.of(), ACTOR);
    }

    @Test
    void invalidSkillSelectionFailsBeforeRunPersistenceOrModelInvocation() {
        when(skills.resolveForRun(List.of("missing")))
                .thenThrow(new IllegalArgumentException("Skill not found: missing"));

        assertThatThrownBy(() -> service.create(command(List.of("missing")), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill not found");

        verify(runs, never()).save(any());
        verify(modelGateway, never()).complete(any());
        verify(conversations, never()).append(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void incompatibleSkillFailsBeforeRunPersistenceOrModelInvocation() {
        SkillRunBinding binding = new SkillRunBinding(
                "python-skill", "Python Skill", "Run a Python helper", "sha256:" + "e".repeat(64),
                "example/skills", "https://github.com/example/skills", "main", "f".repeat(40), "python");
        CompatibilityReport report = new CompatibilityReport(
                false,
                List.of(new CompatibilityReport.Issue(
                        "ERROR", "MISSING_RUNTIME", "python-skill",
                        "Skill requires runtime 'python' >=3.11, but the selected node did not report it.")),
                List.of(),
                List.of(new io.github.yourname.agentstudio.skill.SkillAnalysis.RuntimeRequirement(
                        "python", ">=3.11", "requirements.runtimes")),
                List.of());
        when(skills.resolveForRun(List.of("python-skill"))).thenReturn(List.of(binding));
        when(skillCompatibility.check(any(), any(), any())).thenReturn(report);

        assertThatThrownBy(() -> service.create(command(List.of("python-skill")), ACTOR))
                .isInstanceOf(io.github.yourname.agentstudio.skill.SkillCompatibilityException.class)
                .hasMessageContaining("compatibility check failed")
                .hasMessageContaining("python");

        verify(runs, never()).save(any());
        verify(skills, never()).compileInstructions(any());
        verify(modelGateway, never()).complete(any());
        verify(conversations, never()).append(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void queuedWorkerUsesPersistedAgentPromptAndToolBindingsAfterCatalogChanges() {
        ResolvedToolBinding originalBinding = new ResolvedToolBinding(
                "node:node-1:fs.read",
                "tool_fs_read_original",
                "fs.read",
                "node",
                "fs.read",
                "Read a workspace file",
                RiskLevel.LOW,
                false,
                Map.of("type", "object"),
                Map.of("nodeId", "node-1"));
        ResolvedToolBinding laterBinding = new ResolvedToolBinding(
                "node:node-1:shell.run",
                "tool_shell_run_later",
                "shell.run",
                "node",
                "shell.run",
                "A tool added after the Run was queued",
                RiskLevel.HIGH,
                true,
                Map.of("type", "object"),
                Map.of("nodeId", "node-1"));
        when(agents.get("agent-1")).thenReturn(new AgentDefinitionView(
                "agent-1", "Agent", "", "Original immutable prompt", "model-1", "node:*", true));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of(originalBinding));
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(codingAgentLoop.executeInteraction(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn("done");

        service.create(commandForNode(), ACTOR);
        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        // 模拟管理员在 Run 排队后修改 Agent 和工具目录。worker 不应重新读取这些活动配置。
        when(agents.get("agent-1")).thenReturn(new AgentDefinitionView(
                "agent-1", "Agent", "", "Changed prompt must not be used", "model-1", "shell.run", true));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of(laterBinding));

        queuedWorker.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ResolvedToolBinding>> bindingCaptor = ArgumentCaptor.forClass(List.class);
        verify(codingAgentLoop).executeInteraction(
                anyString(), anyString(), bindingCaptor.capture(), any(), any(), any(), any(), any());
        assertThat(bindingCaptor.getValue()).containsExactly(originalBinding);
        assertThat(persisted.runSpecJson())
                .contains("Original immutable prompt")
                .contains("node:node-1:fs.read")
                .doesNotContain("Changed prompt must not be used")
                .doesNotContain("node:node-1:shell.run");
        verify(agents, times(1)).get("agent-1");
        verify(toolRouter, times(1)).resolve(any(), any(), anyString());
    }

    @Test
    void initialApprovalPersistsRetrievalEvidenceForLaterCitation() {
        ResolvedToolBinding knowledgeBinding = new ResolvedToolBinding(
                "backend:knowledge_search",
                "knowledge_search",
                "knowledge_search",
                "backend",
                "knowledge_search",
                "Search the bound knowledge base",
                RiskLevel.LOW,
                false,
                Map.of("type", "object"),
                Map.of());
        ResolvedToolBinding nodeBinding = new ResolvedToolBinding(
                "node:node-1:fs.write",
                "tool_fs_write",
                "fs.write",
                "node",
                "fs.write",
                "Write a workspace file",
                RiskLevel.HIGH,
                true,
                Map.of("type", "object"),
                Map.of("nodeId", "node-1"));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of(knowledgeBinding, nodeBinding));
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(toolRouter.invoke(any())).thenReturn(new ToolProviderResult(
                "SUCCEEDED",
                true,
                Map.of("matches", List.of(Map.<String, Object>of(
                        "chunkId", 42L,
                        "documentId", "doc-1",
                        "knowledgeBaseId", "kb-1",
                        "sourceName", "Operations guide",
                        "chunkIndex", 1,
                        "quote", "Use after approval.",
                        "score", 0.9))),
                "",
                null));
        when(codingAgentLoop.executeInteraction(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new CodingApprovalRequiredException(
                        "approval-1",
                        "call-1",
                        List.of(new ModelGateway.ModelMessage("user", "Inspect one file"))));

        service.create(commandForNode(), ACTOR);
        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        queuedWorker.run();

        ArgumentCaptor<CodingRunContinuationEntity> continuationCaptor =
                ArgumentCaptor.forClass(CodingRunContinuationEntity.class);
        verify(continuations).save(continuationCaptor.capture());
        assertThat(continuationCaptor.getValue().evidenceJson())
                .contains("kb-1")
                .contains("Operations guide");
        assertThat(continuationCaptor.getValue().webResultsJson()).isEqualTo("[]");
        assertThat(persisted.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
    }

    @Test
    void automaticWebRetrievalCannotBypassImmutableToolSet() {
        when(agents.get("agent-1")).thenReturn(new AgentDefinitionView(
                "agent-1", "Agent", "", "Answer only with authorized tools", "model-1", "local_time", true));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of());
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(modelGateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("no web evidence", 5, 1, "model"));

        service.create(commandWithText("搜索一下最新 Java 新闻"), ACTOR);
        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        queuedWorker.run();

        verify(toolRouter, never()).invoke(any());
        verify(modelGateway).complete(any());
        assertThat(persisted.status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void createPinsPublishedAgentVersionManifestAndMemoryPolicyInRunSpecV2() throws Exception {
        AgentRuntimeDefinitionService runtimeDefinitions = mock(AgentRuntimeDefinitionService.class);
        MemoryRetrievalService memoryRetrieval = mock(MemoryRetrievalService.class);
        MemoryCandidateService memoryCandidates = mock(MemoryCandidateService.class);
        String memoryPolicy = "{\"mode\":\"PERSONALIZED\",\"longTerm\":{\"enabled\":true,\"topK\":3}}";
        when(runtimeDefinitions.resolve("agent-1", ACTOR.tenantId(), ACTOR.userId())).thenReturn(new AgentRuntimeDefinition(
                "agent-1",
                "agent-version-7",
                "sha256:manifest-v7",
                "Published V2 prompt",
                "sha256:prompt-v7",
                "git.diff",
                "model-1",
                List.of(),
                List.of("kb-agent"),
                List.of("mcp-agent"),
                memoryPolicy,
                true));
        service.configureAgentRuntimeDefinitions(runtimeDefinitions);
        service.configureMemoryRetrieval(memoryRetrieval);
        service.configureMemoryCandidates(memoryCandidates);
        when(conversations.personaContext("conversation-1", ACTOR)).thenReturn(new UserPersonaContext(
                "persona-1", "Developer", "Senior developer context", "{\"language\":\"zh-CN\"}"));
        when(memoryRetrieval.retrieve(
                        "agent-1", "persona-1", "Review the project", memoryPolicy, ACTOR))
                .thenReturn(List.of(new MemorySnapshot(
                        "memory-1", MemoryType.PROCEDURAL, "Prefer focused changes.", 1.0, 0.9, null)));
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(knowledge.resolveKnowledgeBaseIds(List.of("kb-agent"), ACTOR)).thenReturn(List.of("kb-agent"));

        service.create(command(List.of()), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        RunSpec spec = new ObjectMapper().findAndRegisterModules()
                .readValue(runCaptor.getValue().runSpecJson(), RunSpec.class);
        assertThat(spec.version()).isEqualTo(RunSpec.CURRENT_VERSION);
        assertThat(spec.agentVersionId()).isEqualTo("agent-version-7");
        assertThat(spec.agentManifestDigest()).isEqualTo("sha256:manifest-v7");
        assertThat(spec.agentMemoryPolicySnapshot()).contains("PERSONALIZED");
        assertThat(spec.agentSystemPrompt()).isEqualTo("Published V2 prompt");
        assertThat(spec.knowledgeBaseIds()).containsExactly("kb-agent");
        assertThat(spec.mcpConnectionIds()).containsExactly("mcp-agent");
        assertThat(spec.memorySnapshots()).singleElement().satisfies(memory -> {
            assertThat(memory.id()).isEqualTo("memory-1");
            assertThat(memory.content()).isEqualTo("Prefer focused changes.");
        });
        assertThat(spec.userPersonaId()).isEqualTo("persona-1");
        assertThat(spec.userPersonaSnapshotJson()).contains("Developer").contains("zh-CN");
        verify(agents, never()).get("agent-1");

        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(modelGateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "done", 10, 2, "test-model"));
        queuedWorker.run();

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> modelRequest =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(modelGateway).complete(modelRequest.capture());
        assertThat(modelRequest.getValue().messages().getFirst().content())
                .contains("Selected user persona")
                .contains("Senior developer context")
                .contains("Recalled memory")
                .contains("Prefer focused changes.");
        verify(memoryCandidates).capture(
                "agent-1",
                "persona-1",
                "conversation-1",
                persisted.id(),
                "Review the project",
                memoryPolicy,
                ACTOR);
    }

    @Test
    void conversationalRunConsultsBoundAgentAndLetsPrimaryAgentSynthesize() throws Exception {
        AgentRuntimeDefinitionService runtimeDefinitions = mock(AgentRuntimeDefinitionService.class);
        AgentCollaboratorRuntimeDefinition collaborator = new AgentCollaboratorRuntimeDefinition(
                "research-agent",
                "research-version-3",
                "sha256:research-manifest",
                "Research analyst",
                "AS_TOOL",
                "Use for evidence analysis",
                "You verify evidence carefully.",
                "sha256:research-prompt",
                "model-1");
        when(runtimeDefinitions.resolve("agent-1", ACTOR.tenantId(), ACTOR.userId())).thenReturn(
                new AgentRuntimeDefinition(
                        "agent-1",
                        "agent-version-8",
                        "sha256:manifest-v8",
                        "Primary coordinator prompt",
                        "sha256:prompt-v8",
                        "",
                        "model-1",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(collaborator),
                        "{}",
                        true));
        service.configureAgentRuntimeDefinitions(runtimeDefinitions);
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(modelGateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "",
                        10,
                        2,
                        "model",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-1",
                                "consult_agent_1",
                                Map.of("task", "Check the evidence"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "The evidence supports the claim with one limitation.", 12, 5, "model"))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Final answer with the expert limitation.", 18, 7, "model"));

        service.create(commandWithText("Assess this claim"), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        RunSpec spec = new ObjectMapper().findAndRegisterModules()
                .readValue(persisted.runSpecJson(), RunSpec.class);
        assertThat(spec.collaboratorBindings()).singleElement().isEqualTo(collaborator);
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        queuedWorker.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requestCaptor =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(modelGateway, times(3)).complete(requestCaptor.capture());
        List<ModelGateway.ModelCompletionRequest> requests = requestCaptor.getAllValues();
        assertThat(requests.get(0).tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("consult_agent_1");
            assertThat(tool.description()).contains("Research analyst").contains("evidence analysis");
        });
        assertThat(requests.get(1).modelProfileId()).isEqualTo("model-1");
        assertThat(requests.get(1).messages().getFirst().content())
                .contains("You verify evidence carefully")
                .contains("bounded expert collaborator");
        assertThat(requests.get(2).messages())
                .anySatisfy(message -> assertThat(message.content())
                        .contains("The evidence supports the claim"));
        assertThat(persisted.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(persisted.finalAnswer()).isEqualTo("Final answer with the expert limitation.");
        verify(events).publish(
                persisted.id(),
                RunEventType.STEP_STARTED,
                "collaborator=Research analyst, mode=AS_TOOL",
                ACTOR);
    }

    @Test
    void handoffDelegatesTheTaskDirectlyToThePublishedCollaborator() throws Exception {
        AgentRuntimeDefinitionService runtimeDefinitions = mock(AgentRuntimeDefinitionService.class);
        AgentCollaboratorRuntimeDefinition collaborator = new AgentCollaboratorRuntimeDefinition(
                "research-agent",
                "research-version-4",
                "sha256:research-manifest",
                "Research analyst",
                "HANDOFF",
                "When the specialist should own the task",
                "You verify evidence carefully.",
                "sha256:research-prompt",
                "model-1");
        when(runtimeDefinitions.resolve("agent-1", ACTOR.tenantId(), ACTOR.userId())).thenReturn(
                new AgentRuntimeDefinition(
                        "agent-1", "agent-version-9", "sha256:manifest-v9", "Primary prompt",
                        "sha256:prompt-v9", "", "model-1", List.of(), List.of(), List.of(),
                        List.of(collaborator), "{}", true));
        service.configureAgentRuntimeDefinitions(runtimeDefinitions);
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(modelGateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "Delegated answer from the specialist.", 14, 6, "model"));

        service.create(commandWithText("Assess this claim"), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        queuedWorker.run();

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requestCaptor =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(modelGateway).complete(requestCaptor.capture());
        assertThat(requestCaptor.getValue().messages().getFirst().content())
                .contains("You verify evidence carefully")
                .contains("You own this task as the delegated Agent");
        assertThat(persisted.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(persisted.finalAnswer()).isEqualTo("Delegated answer from the specialist.");
        verify(events).publish(
                persisted.id(),
                RunEventType.STEP_STARTED,
                "collaborator=Research analyst, mode=HANDOFF",
                ACTOR);
    }

    @Test
    void createRejectsCapabilitiesOutsidePublishedAgentVersion() {
        AgentRuntimeDefinitionService runtimeDefinitions = mock(AgentRuntimeDefinitionService.class);
        when(runtimeDefinitions.resolve("agent-1", ACTOR.tenantId(), ACTOR.userId())).thenReturn(new AgentRuntimeDefinition(
                "agent-1",
                "agent-version-7",
                "sha256:manifest-v7",
                "Published V2 prompt",
                "sha256:prompt-v7",
                "git.diff",
                "model-1",
                List.of("review-skill"),
                List.of("kb-agent"),
                List.of("mcp-agent"),
                "{\"mode\":\"CONVERSATION\"}",
                true));
        service.configureAgentRuntimeDefinitions(runtimeDefinitions);

        assertThatThrownBy(() -> service.create(command(List.of("unbound-skill")), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not bound to the published Agent version")
                .hasMessageContaining("unbound-skill");
        verify(runs, never()).save(any());
    }

    @Test
    void selectedNodeGreetingUsesDynamicToolLoopWithoutForcingANativeToolCall() {
        ResolvedToolBinding nodeBinding = new ResolvedToolBinding(
                "node:node-1:browser.open",
                "tool_browser_open",
                "browser.open",
                "node",
                "browser.open",
                "Open a browser page",
                RiskLevel.LOW,
                false,
                Map.of("type", "object"),
                Map.of("nodeId", "node-1"));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of(nodeBinding));
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(codingAgentLoop.executeInteraction(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn("你好！");
        service.create(new CreateRunCommand(
                "conversation-1", "你好呀", "model-1", "agent-1", List.of(), List.of(), List.of(),
                List.of(), "node-1", null), ACTOR);
        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        queuedWorker.run();

        verify(codingAgentLoop).executeInteraction(
                anyString(), anyString(), any(), any(), any(), any(), any(), any());
        verify(nodes).validateExecutionTarget("node-1", ACTOR);
        verify(nodes, never()).codingEvidence(anyString(), any());
        assertThat(persisted.runSpecJson()).contains("\"executionMode\":\"NODE_INTERACTION\"");
        assertThat(persisted.status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void workerLoadsPersistedBindingsAndSendsCompiledSkillTextToTheModel() {
        SkillRunBinding binding = new SkillRunBinding(
                "testing-skill", "Testing Skill", "Always test", "sha256:" + "c".repeat(64),
                "example/skills", "https://github.com/example/skills", "main", "d".repeat(40), "testing");
        String compiledInstruction = "完整 Skill 指令：修改完成后必须执行测试。";
        when(skills.resolveForRun(List.of("testing-skill"))).thenReturn(List.of(binding));
        when(skills.compileInstructions(List.of(binding))).thenReturn(compiledInstruction);
        when(agents.get("agent-1")).thenReturn(new AgentDefinitionView(
                "agent-1", "Agent", "", "Agent system instruction", "model-1", "", true));
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(modelGateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("done", 10, 2, "model"));

        service.create(command(List.of("testing-skill")), ACTOR);
        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        queuedWorker.run();

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requestCaptor =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(modelGateway).complete(requestCaptor.capture());
        String systemPrompt = requestCaptor.getValue().messages().getFirst().content();
        assertThat(systemPrompt)
                .contains("Agent system instruction")
                .contains(compiledInstruction)
                .contains("Enabled Skill instructions");
        assertThat(persisted.status()).isEqualTo(RunStatus.SUCCEEDED);
        verify(skills, times(2)).compileInstructions(List.of(binding));
    }

    @Test
    void streamingModelPublishesOnlyFilteredDeltasBeforeTheFinalAnswer() {
        List<RunEventType> eventTypes = new ArrayList<>();
        List<String> eventPayloads = new ArrayList<>();
        doAnswer(invocation -> {
            eventTypes.add(invocation.getArgument(1));
            eventPayloads.add(invocation.getArgument(2));
            return null;
        }).when(events).publish(anyString(), any(), anyString(), any());
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(conversations.history("conversation-1", ACTOR)).thenReturn(List.of());
        when(modelGateway.supportsStreaming()).thenReturn(true);
        when(modelGateway.stream(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onToken = invocation.getArgument(1);
            // 模拟真实 SSE 将隐藏标签拆到多个网络分片中。
            onToken.accept("Visible <thi");
            onToken.accept("nk>internal reasoning</think>");
            onToken.accept(" final");
            return new ModelGateway.ModelAnswer(
                    "Visible <think>internal reasoning</think> final", 8, 4, "stream-model");
        });

        service.create(commandWithText("Give a concise answer"), ACTOR);
        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        AgentRunEntity persisted = runCaptor.getValue();
        when(runs.findById(persisted.id())).thenReturn(Optional.of(persisted));
        when(runs.findByIdAndTenantId(persisted.id(), ACTOR.tenantId())).thenReturn(Optional.of(persisted));

        queuedWorker.run();

        List<String> deltas = new ArrayList<>();
        for (int index = 0; index < eventTypes.size(); index++) {
            if (eventTypes.get(index) == RunEventType.TOKEN_DELTA) {
                deltas.add(eventPayloads.get(index));
            }
        }
        assertThat(String.join("", deltas))
                .isEqualTo("Visible  final")
                .doesNotContain("internal reasoning")
                .doesNotContain("<think>");
        assertThat(eventTypes.indexOf(RunEventType.TOKEN_DELTA))
                .isLessThan(eventTypes.indexOf(RunEventType.FINAL_ANSWER));
        assertThat(eventTypes.stream().filter(type -> type == RunEventType.TOKEN_DELTA).count()).isEqualTo(2);
        verify(modelGateway, never()).complete(any());
    }

    @Test
    void createRoutesComputerControlToTheReadyNodeWithoutTextBasedToolFiltering() {
        ResolvedToolBinding binding = new ResolvedToolBinding(
                "node:node-system:system.desktop.organize.list",
                "tool_system_desktop_organize_list",
                "system.desktop.organize.list",
                "node",
                "system.desktop.organize.list",
                "Inspect the desktop files that may be organized",
                RiskLevel.HIGH,
                true,
                Map.of("type", "object"),
                Map.of("nodeId", "node-system"));
        ResolvedToolBinding wallpaper = new ResolvedToolBinding(
                "node:node-system:system.desktop.set_wallpaper",
                "tool_system_desktop_set_wallpaper",
                "system.desktop.set_wallpaper",
                "node",
                "system.desktop.set_wallpaper",
                "Set wallpaper",
                RiskLevel.HIGH,
                true,
                Map.of("type", "object"),
                Map.of("nodeId", "node-system"));
        when(nodes.resolveComputerControlNodeId(ACTOR)).thenReturn("node-system");
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(nodes.get("node-system", ACTOR)).thenReturn(new NodeDetailView(
                new NodeConnectionView(
                        "node-system", "My PC", "host", "Windows", "amd64", "test", NodeKind.REGISTERED, null,
                        Map.of(), Set.of(), true, NodeStatus.ONLINE, null, null, null),
                List.of()));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of(binding, wallpaper));

        service.create(new CreateRunCommand(
                "conversation-1", "Organize my desktop", "model-1", "agent-1", List.of(), List.of(), List.of(),
                List.of("computer:*"), null, null), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).save(runCaptor.capture());
        assertThat(runCaptor.getValue().runSpecJson())
                .contains("node-system")
                .contains("system.*")
                .doesNotContain("computer:*")
                .contains("system.desktop.organize.list")
                .contains("system.desktop.set_wallpaper")
                .contains("\"executionMode\":\"NODE_INTERACTION\"");
    }

    @Test
    void createRoutesAnExplicitLocalProjectPathToTheReadyNode() {
        ResolvedToolBinding binding = new ResolvedToolBinding(
                "node:node-system:system.fs.list",
                "tool_system_fs_list",
                "system.fs.list",
                "node",
                "system.fs.list",
                "List one local project directory",
                RiskLevel.MEDIUM,
                true,
                Map.of("type", "object"),
                Map.of("nodeId", "node-system"));
        when(nodes.resolveComputerControlNodeId(ACTOR)).thenReturn("node-system");
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(nodes.get("node-system", ACTOR)).thenReturn(new NodeDetailView(
                new NodeConnectionView(
                        "node-system", "My PC", "host", "Windows", "amd64", "test", NodeKind.REGISTERED, null,
                        Map.of(), Set.of(), true, NodeStatus.ONLINE, null, null, null),
                List.of()));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of(binding));

        service.create(new CreateRunCommand(
                "conversation-1", "Fix the backend project at D:\\ai\\spring-agent-studio-backend and run its tests.",
                "model-1", "agent-1", List.of(), List.of(), List.of(), List.of(), null, null), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(nodes).resolveComputerControlNodeId(ACTOR);
        verify(runs).save(runCaptor.capture());
        assertThat(runCaptor.getValue().runSpecJson())
                .contains("node-system")
                .contains("system.fs.list")
                .contains("system.process.start")
                .contains("\"executionMode\":\"NODE_INTERACTION\"");
    }

    @Test
    void createResolvesAutoCodingRunToTheMatchingSandboxAndPinsTheConcreteNode() {
        ResolvedToolBinding binding = new ResolvedToolBinding(
                "node:sandbox-linux:fs.read",
                "tool_fs_read",
                "fs.read",
                "node",
                "fs.read",
                "Read a workspace file",
                RiskLevel.LOW,
                false,
                Map.of("type", "object"),
                Map.of("nodeId", "sandbox-linux"));
        when(nodes.resolveSandboxNodeId(List.of("linux", "java-21"), List.of("fs.read"), ACTOR))
                .thenReturn("sandbox-linux");
        when(skills.resolveForRun(List.of())).thenReturn(List.of());
        when(skills.compileInstructions(List.of())).thenReturn("");
        when(nodes.get("sandbox-linux", ACTOR)).thenReturn(new NodeDetailView(
                new NodeConnectionView(
                        "sandbox-linux", "Linux sandbox", "sandbox", "Linux", "amd64", "test", NodeKind.SANDBOX, null,
                        Map.of(), Set.of(), Set.of("linux", "java-21"), true, NodeStatus.ONLINE, null, null, null),
                List.of()));
        when(toolRouter.resolve(any(), any(), anyString())).thenReturn(List.of(binding));

        service.create(new CreateRunCommand(
                "conversation-1", "Inspect the project", "model-1", "agent-1", List.of(), List.of(), List.of(),
                List.of("fs.read"), "auto", ".", List.of(), List.of("linux", "java-21")), ACTOR);

        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(nodes).resolveSandboxNodeId(List.of("linux", "java-21"), List.of("fs.read"), ACTOR);
        verify(runs).save(runCaptor.capture());
        assertThat(runCaptor.getValue().runSpecJson())
                .contains("sandbox-linux")
                .doesNotContain("\"nodeId\":\"auto\"");
    }

    private static CreateRunCommand command(List<String> skillIds) {
        return new CreateRunCommand(
                "conversation-1",
                "Review the project",
                "model-1",
                "agent-1",
                List.of(),
                skillIds,
                List.of(),
                List.of(),
                null,
                null);
    }

    private static CreateRunCommand commandForNode() {
        return new CreateRunCommand(
                "conversation-1",
                "Inspect one file",
                "model-1",
                "agent-1",
                List.of(),
                List.of(),
                List.of(),
                List.of("fs.read"),
                "node-1",
                ".");
    }

    private static CreateRunCommand commandWithText(String text) {
        return new CreateRunCommand(
                "conversation-1",
                text,
                "model-1",
                "agent-1",
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
    }
}
