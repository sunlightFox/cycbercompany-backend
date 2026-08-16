package io.github.yourname.cycbercompany.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.yourname.cycbercompany.model.ModelGateway;
import io.github.yourname.cycbercompany.model.ModelRateLimitException;
import io.github.yourname.cycbercompany.model.ModelTransientException;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.ResolvedToolBinding;
import io.github.yourname.cycbercompany.tool.RiskLevel;
import io.github.yourname.cycbercompany.tool.ApprovalMode;
import io.github.yourname.cycbercompany.tool.ToolInvocationRequest;
import io.github.yourname.cycbercompany.tool.ToolProviderResult;
import io.github.yourname.cycbercompany.tool.ToolCleanupResult;
import io.github.yourname.cycbercompany.tool.ToolRouter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodingAgentLoopTest {

    @Test
    void preservesProcessesOnlyForTheLatestExplicitServiceRequest() {
        assertThat(CodingAgentLoop.shouldPreserveManagedProcesses(List.of(
                new ModelGateway.ModelMessage("user", "Start a local server on port 9000."))))
                .isTrue();
        assertThat(CodingAgentLoop.shouldPreserveManagedProcesses(List.of(
                new ModelGateway.ModelMessage("user", "Start a local server on port 9000."),
                new ModelGateway.ModelMessage("assistant", "The server is running."),
                new ModelGateway.ModelMessage("user", "Check the README file."))))
                .isFalse();
        assertThat(CodingAgentLoop.shouldPreserveManagedProcesses(List.of(
                new ModelGateway.ModelMessage("user", "Stop the local server on port 9000."))))
                .isFalse();
    }

    @Test
    void nodeInteractionLetsTheModelDecideWhetherToCallATool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("你好！", null, null, "test"));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-a", "model-a", List.of(binding("node_list", "system.desktop.organize.list")),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "你好呀"))), actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.ON_REQUEST);

        assertThat(answer).isEqualTo("你好！");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        verify(tools, never()).invoke(any());
    }

    @Test
    void platformInteractionExposesBackendAndSkillToolsWithoutSelectingANode() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("可用工具已列出。", null, null, "test"));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executePlatform(
                "run-a", "model-a", List.of(
                        binding("backend_time", "local_time"),
                        binding("skill_resource", "skill.resource.read")),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "有什么工具？"))), actor,
                ApprovalMode.ON_REQUEST, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).isEqualTo("可用工具已列出。");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        assertThat(request.getValue().tools()).extracting(ModelGateway.ModelTool::description)
                .anyMatch(description -> description.contains("local_time"))
                .anyMatch(description -> description.contains("skill.resource.read"));
        assertThat(executionGuidance(request.getValue()))
                .contains("Complete the user's request directly.")
                .contains("call web_search once");
        verify(tools, never()).invoke(any());
    }

    @Test
    void platformTextResponseStreamsImmediatelyWithoutAToolRoundTrip() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        when(gateway.supportsStreaming()).thenReturn(true);
        when(gateway.stream(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("即时");
            onToken.accept("回复");
            return new ModelGateway.ModelAnswer("即时回复", null, null, "stream-model");
        });
        when(tools.cleanup("run-platform-stream", actor)).thenReturn(List.of());

        CodingAgentLoop loop = new CodingAgentLoop(gateway, tools, events);
        String answer = loop.executePlatform(
                "run-platform-stream", "model-a", List.of(binding("backend_time", "local_time")),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "你好"))), actor,
                ApprovalMode.ON_REQUEST, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).isEqualTo("即时回复");
        verify(events).publish("run-platform-stream", RunEventType.TOKEN_DELTA, "即时回复", actor);
        verify(tools, never()).invoke(any());
        assertThat(loop.consumeFinalAnswerStreamed("run-platform-stream")).isTrue();
    }

    @Test
    void platformConversationRecoversAnEmptyTextTurnWithoutInventingAToolResult() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model"))
                .thenReturn(new ModelGateway.ModelAnswer("程序员为什么不喜欢大自然？因为那里有太多 bug。", null, null, "model"));
        when(tools.cleanup("run-joke", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executePlatform(
                "run-joke", "model-a", List.of(binding("backend_time", "local_time")),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "讲个笑话"))), actor,
                ApprovalMode.ON_REQUEST, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).contains("bug");
        verify(tools, never()).invoke(any());
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(2)).complete(requests.capture());
        assertThat(requests.getAllValues().getLast().tools()).isEmpty();
    }

    @Test
    void platformRunReusesTheFirstWebSearchResultInsteadOfWaitingForADuplicateSearch() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding webSearch = binding("backend_web_search", "web_search", false, "backend");
        ModelGateway.ModelToolCall first = new ModelGateway.ModelToolCall(
                "call-1", "backend_web_search", Map.of("query", "latest Musk news"));
        ModelGateway.ModelToolCall duplicate = new ModelGateway.ModelToolCall(
                "call-2", "backend_web_search", Map.of("query", "Musk news today"));
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model", List.of(first), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model", List.of(duplicate), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Here is the news summary.", null, null, "model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(
                "query", "latest Musk news",
                "results", List.of(new io.github.yourname.cycbercompany.tool.WebSearchResult(
                        "News", "https://example.test/news", "short result", "tavily", "TAVILY", null,
                        new io.github.yourname.cycbercompany.tool.WebEvidence(
                                "News", "verified excerpt", true, true, "readable")))), "", null));
        when(tools.cleanup("run-web", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executePlatform(
                "run-web", "model-a", List.of(webSearch),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Find today's Musk news"))),
                actor, ApprovalMode.FULL_ACCESS, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).isEqualTo("Here is the news summary.");
        verify(tools, times(1)).invoke(any());
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(3)).complete(requests.capture());
        assertThat(requests.getAllValues().get(1).tools())
                .noneMatch(tool -> tool.name().equals("backend_web_search"));
    }

    @Test
    void platformRunSuppressesDuplicateWebSearchCallsInOneModelResponse() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding webSearch = binding("backend_web_search", "web_search", false, "backend");
        ModelGateway.ModelToolCall first = new ModelGateway.ModelToolCall(
                "call-1", "backend_web_search", Map.of("query", "latest AI news"));
        ModelGateway.ModelToolCall duplicate = new ModelGateway.ModelToolCall(
                "call-2", "backend_web_search", Map.of("query", "AI news today"));
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model", List.of(first, duplicate), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Here is the news summary.", null, null, "model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(
                "query", "latest AI news", "results", List.of()), "", null));
        when(tools.cleanup("run-web-batch", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executePlatform(
                "run-web-batch", "model-a", List.of(webSearch),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Find today's AI news"))),
                actor, ApprovalMode.FULL_ACCESS, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).isEqualTo("Here is the news summary.");
        verify(tools, times(1)).invoke(any());
        verify(gateway, times(2)).complete(any());
    }

    @Test
    void platformRunUsesToolFreeDeliveryWhenTheModelRepeatsSearchOnTheNextTurn() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding webSearch = binding("backend_web_search", "web_search", false, "backend");
        ModelGateway.ModelToolCall search = new ModelGateway.ModelToolCall(
                "call-1", "backend_web_search", Map.of("query", "DeepSeek hermes"));
        ModelGateway.ModelToolCall repeated = new ModelGateway.ModelToolCall(
                "call-2", "backend_web_search", Map.of("query", "DeepSeek hermes"));
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model", List.of(search), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model", List.of(repeated), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Hermes is not a DeepSeek product; the search results appear unrelated.",
                        null, null, "model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(
                "query", "DeepSeek hermes", "results", List.of()), "", null));
        when(tools.cleanup("run-web-repeat", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executePlatform(
                "run-web-repeat", "model-a", List.of(webSearch),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "deepseek hermes是什么？"))),
                actor, ApprovalMode.FULL_ACCESS, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).startsWith("Hermes is not a DeepSeek product");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(3)).complete(requests.capture());
        assertThat(requests.getValue().tools()).isEmpty();
        verify(tools, times(1)).invoke(any());
    }

    @Test
    void platformRunDoesNotPublishOrPersistMiniMaxToolProtocolAsFinalText() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding webSearch = binding("backend_web_search", "web_search", false, "backend");
        ModelGateway.ModelToolCall search = new ModelGateway.ModelToolCall(
                "call-1", "backend_web_search", Map.of("query", "recent phones"));
        String malformed = "让我用其他方式再尝试搜索。]<]minimax[><tool_call><invoke name=\"web_search\"/>";
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model", List.of(search), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model"))
                .thenReturn(new ModelGateway.ModelAnswer(malformed, null, null, "model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(
                "query", "recent phones", "results", List.of()), "", null));
        when(tools.cleanup("run-malformed-final", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executePlatform(
                "run-malformed-final", "model-a", List.of(webSearch),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "搜索最近发布的手机"))),
                actor, ApprovalMode.FULL_ACCESS, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).contains("网页搜索已完成");
        verify(events, never()).publish(eq("run-malformed-final"), eq(RunEventType.TOKEN_DELTA),
                contains("让我用其他方式"), eq(actor));
    }

    @Test
    void platformRunReSummarizesVerifiedWebEvidenceInACleanToolFreeContext() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding webSearch = binding("backend_web_search", "web_search", false, "backend");
        ModelGateway.ModelToolCall search = new ModelGateway.ModelToolCall(
                "call-1", "backend_web_search", Map.of("query", "recent phones"));
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model", List.of(search), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "model"))
                .thenReturn(new ModelGateway.ModelAnswer("]<]minimax[><tool_call>", null, null, "model"))
                .thenReturn(new ModelGateway.ModelAnswer("近期可关注 OPPO A7 Pro Max 发布会；请以官方页面为准。", null, null, "model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(
                "query", "recent phones",
                "results", List.of(new io.github.yourname.cycbercompany.tool.WebSearchResult(
                        "OPPO A7 Pro Max新品发布会", "https://example.test/oppo", "发布会信息", "tavily", "TAVILY", null,
                        new io.github.yourname.cycbercompany.tool.WebEvidence(
                                "OPPO A7 Pro Max新品发布会", "OPPO A7 Pro Max将于近期发布。", true, true, "readable")))), "", null));
        when(tools.cleanup("run-web-resummary", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executePlatform(
                "run-web-resummary", "model-a", List.of(webSearch),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "搜索最近发布的手机"))),
                actor, ApprovalMode.FULL_ACCESS, io.github.yourname.cycbercompany.tool.AgentApprovalPolicy.sessionOnly());

        assertThat(answer).contains("OPPO A7 Pro Max");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(4)).complete(requests.capture());
        assertThat(requests.getValue().tools()).isEmpty();
        assertThat(requests.getValue().messages()).extracting(ModelGateway.ModelMessage::content)
                .anyMatch(content -> content != null && content.contains("Verified web evidence"));
    }

    @Test
    void webSearchFallbackDeliversVerifiedUrlsWhenTheModelKeepsRequestingTools() {
        ToolProviderResult outcome = new ToolProviderResult("SUCCEEDED", true, Map.of(
                "results", List.of(new io.github.yourname.cycbercompany.tool.WebSearchResult(
                        "Official source", "https://example.test/official", "Verified summary", "tavily", "TAVILY", null,
                        new io.github.yourname.cycbercompany.tool.WebEvidence(
                                "Official source", "Verified summary", true, true, "readable")))), "", null);

        assertThat(CodingAgentLoop.webSearchFallback(outcome))
                .contains("https://example.test/official")
                .contains("Official source")
                .doesNotContain("Verified summary");
    }

    @Test
    void explicitDownloadableOfficeFileRequestRequiresTheArtifactTool() {
        assertThat(CodingAgentLoop.requiresArtifactDelivery(
                List.of(binding("backend_artifact", "create_artifact")),
                List.of(new ModelGateway.ModelMessage(
                        "user", "Generate and download a report.docx file."))))
                .isTrue();
        assertThat(CodingAgentLoop.requiresArtifactDelivery(
                List.of(binding("backend_artifact", "create_artifact")),
                List.of(new ModelGateway.ModelMessage("user", "Explain the Word document format."))))
                .isFalse();
        assertThat(CodingAgentLoop.requiresArtifactDelivery(
                List.of(binding("backend_artifact", "create_artifact")),
                List.of(
                        new ModelGateway.ModelMessage("user", "Create father.docx as a downloadable Word file."),
                        new ModelGateway.ModelMessage("assistant", "I have the document requirements."),
                        new ModelGateway.ModelMessage("user", "\u7236\u4eb2\u3002\u76f4\u63a5\u5f00\u59cb"))))
                .isTrue();
        assertThat(CodingAgentLoop.requiresArtifactDelivery(
                List.of(binding("backend_artifact", "create_artifact")),
                List.of(new ModelGateway.ModelMessage(
                        "user", "Prepare father.docx, but wait for me to say start before generating it."))))
                .isFalse();
        assertThat(CodingAgentLoop.requiresArtifactDelivery(
                List.of(binding("backend_artifact", "create_artifact")),
                List.of(
                        new ModelGateway.ModelMessage(
                                "user", "Prepare father.docx, but wait for me to say start before generating it."),
                        new ModelGateway.ModelMessage("user", "Start now."))))
                .isTrue();
    }

    @Test
    void artifactToolResultAlwaysProducesATrustedDownloadLink() {
        ToolProviderResult outcome = new ToolProviderResult("SUCCEEDED", true, Map.of(
                "artifact", new io.github.yourname.cycbercompany.artifact.ArtifactView(
                        "art-father", "run-father", "document", "father.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2048,
                        "sha256:" + "a".repeat(64), "/api/v1/artifacts/art-father", java.time.Instant.EPOCH)), "", null);

        assertThat(CodingAgentLoop.artifactDelivery(outcome))
                .isEqualTo("- [father.docx](/api/v1/artifacts/art-father)");
    }

    @Test
    void explicitlyNamedNodeToolMayReturnAReasonedTextAnswerWithoutCallingIt() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding shell = binding("node_shell", "system.shell.run");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "The shell tool is unavailable.", null, null, "test"));

        assertThat(new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class))
                .executeInteraction(
                        "run-a", "model-a", List.of(shell),
                        new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                                "user", "Use system.shell.run to execute echo approval-test."))),
                        actor,
                        io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                        ApprovalMode.ON_REQUEST))
                .isEqualTo("The shell tool is unavailable.");

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        verify(tools, never()).invoke(any());
    }

    @Test
    void explicitNativeInspectionMayReturnAReasonedTextAnswerWithoutCallingATool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding read = binding("tool_fs_read_9d13a3775656", "fs.read");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "The file says ready.", null, null, "test"));

        assertThat(new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class))
                .executeInteraction(
                        "run-a", "model-a", List.of(read),
                        new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                                "user", "Read status.txt from this computer."))), actor,
                        io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                        ApprovalMode.FULL_ACCESS))
                .isEqualTo("The file says ready.");
        verify(tools, never()).invoke(any());
    }

    @Test
    void explicitStateChangeRequestLeavesTheToolDecisionToTheModel() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding shell = binding("tool_system_shell_run_123456", "system.shell.run");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "The project was created.", null, null, "test"));

        assertThat(new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class))
                .executeInteraction(
                        "run-a", "model-a", List.of(shell),
                        new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                                "user", "Create a frontend project on the desktop."))),
                        actor,
                        io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                        ApprovalMode.FULL_ACCESS))
                .isEqualTo("The project was created.");

        verify(tools, never()).invoke(any());
    }

    @Test
    void returnsVerifiedShellStdoutWithoutAnotherModelTurnWhenExactOutputWasRequested() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding shell = binding("tool_system_shell_run_123456", "system.shell.run");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "Running the program.",
                null,
                null,
                "test",
                List.of(new ModelGateway.ModelToolCall("call-shell", "system.shell.run", Map.of("command", "java HexValue"))),
                "tool_calls"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult(
                "SUCCEEDED",
                true,
                Map.of("value", Map.of("stdout", "ff\n", "stderr", "", "exitCode", 0)),
                "",
                null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-a",
                "model-a",
                List.of(shell),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                        "user", "Run the program and return the exact output."))),
                actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("ff");
        verify(gateway, times(1)).complete(any());
        verify(tools).invoke(any());
    }

    @Test
    void turnsAnEchoedToolResultIntoASafeFinalSummary() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding read = binding("node_read", "fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Reading the file.",
                        null,
                        null,
                        "test",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-read", "fs.read", Map.of("path", "snake/index.html"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Tool execution completed. Result: {\"status\":\"SUCCEEDED\",\"tool\":\"fs.read\","
                                + "\"result\":{\"path\":\"snake/index.html\",\"content\":\"private file body\"}}",
                        null,
                        null,
                        "test"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult(
                "SUCCEEDED", true, Map.of("path", "snake/index.html", "content", "private file body"), "", null));
        when(tools.cleanup("run-echo", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-echo",
                "model-a",
                List.of(read),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Read snake/index.html"))),
                actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("Verified tool result (fs.read): status=SUCCEEDED, path=snake/index.html");
        assertThat(answer).doesNotContain("private file body");
    }

    @Test
    void explicitLocalActionLeavesTheToolDecisionToTheModel() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding shell = binding(
                "tool_system_shell_run_123456", "system.shell.run", false, "in_process_local");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "I cannot access the local computer.", null, null, "test"));

        assertThat(new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class))
                .executeInteraction(
                        "run-a", "model-a", List.of(shell),
                        new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                                "user", "启动一下项目"))),
                        actor,
                        io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                        ApprovalMode.ON_REQUEST))
                .isEqualTo("I cannot access the local computer.");

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        verify(tools, never()).invoke(any());
    }

    @Test
    void retriesAnExplicitStateChangeAfterItsFirstToolCallFails() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding list = binding("tool_system_fs_list_123456", "system.fs.list");
        ResolvedToolBinding mkdir = binding("tool_system_fs_mkdir_123456", "system.fs.mkdir");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Listing target.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-list", "tool_system_fs_list", Map.of("path", "C:\\Desktop\\new-project"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Creating target.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-mkdir", "tool_system_fs_mkdir", Map.of("path", "C:\\Desktop\\new-project"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Project created.", null, null, "test"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("FAILED", false, Map.of(), "Target does not exist", null))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executeInteraction(
                "run-a", "model-a", List.of(list, mkdir),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                        "user", "Create a frontend project on the desktop."))),
                actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("Project created.");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(3)).complete(requests.capture());
        assertThat(requests.getAllValues().get(1).toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        verify(tools, times(2)).invoke(any());
    }

    @Test
    void continuesAfterAnUnconfirmedNodeCallSoTheModelCanInspectStateAfterReconnect() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding write = binding("node_write", "system.fs.write");
        ResolvedToolBinding read = binding("node_read", "system.fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("Writing the page.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-write", "node_write", Map.of("path", "index.html", "content", "page"))), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Inspecting the uncertain write.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-read", "node_read", Map.of("path", "index.html"))), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The project state was inspected after reconnect.", null, null, "test"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("UNKNOWN", false, Map.of(), "Node is not connected: node-a", null))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of("content", "page"), null, null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executeInteraction(
                "run-a", "model-a", List.of(write, read),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                        "user", "Create a frontend project on the desktop."))),
                actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("The project state was inspected after reconnect.");
        verify(tools, times(2)).invoke(any());
    }

    @Test
    void stopsAfterFourConsecutiveToolFailuresWithTheLastFailureReason() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding shell = binding("tool_system_shell_run_123456", "system.shell.run");
        when(gateway.complete(any())).thenReturn(
                new ModelGateway.ModelAnswer("Try one.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-1", "tool_system_shell_run", Map.of("command", "bad-1"))), "tool_calls"),
                new ModelGateway.ModelAnswer("Try two.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-2", "tool_system_shell_run", Map.of("command", "bad-2"))), "tool_calls"),
                new ModelGateway.ModelAnswer("Try three.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-3", "tool_system_shell_run", Map.of("command", "bad-3"))), "tool_calls"),
                new ModelGateway.ModelAnswer("Try four.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-4", "tool_system_shell_run", Map.of("command", "bad-4"))), "tool_calls"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult(
                "FAILED", false, Map.of(), "Command exited with code 1.", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        assertThatThrownBy(() -> new CodingAgentLoop(gateway, tools, events).execute(
                "run-a", "model-a", List.of(shell),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Create the project."))), actor))
                .hasMessageContaining("4 consecutive tool failures")
                .hasMessageContaining("Command exited with code 1.");

        verify(tools, times(4)).invoke(any());
        verify(events, times(4)).publish(
                "run-a", RunEventType.TOOL_CALL_FAILED,
                "tool=system.shell.run, error=Command exited with code 1.", actor);
    }

    @Test
    void blocksAnIdenticalFailedNodeInteractionBeforeItReachesTheNodeAgain() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding desktopDelete = binding(
                "node_desktop_delete", "system.desktop.organize.delete");
        ResolvedToolBinding filesystemDelete = binding("node_filesystem_delete", "system.fs.delete");
        Map<String, Object> target = Map.of("path", "C:\\fixture\\images");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("Deleting the folder.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-1", "node_desktop_delete", target)), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Trying again.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-2", "node_desktop_delete", target)), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Using the folder-capable tool.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-3", "node_filesystem_delete", target)), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The fixture directory was deleted.", null, null, "test"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("FAILED", false, Map.of(), "Target is a directory", null))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of("deleted", true), null, null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).executeInteraction(
                "run-a", "model-a", List.of(desktopDelete, filesystemDelete),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage(
                        "user", "Delete the fixture images directory."))),
                actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("The fixture directory was deleted.");
        verify(tools, times(2)).invoke(any());
        verify(events).publish(
                "run-a",
                RunEventType.TOOL_CALL_FAILED,
                "tool=system.desktop.organize.delete, reason=duplicate failed call blocked",
                actor);
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(4)).complete(requests.capture());
        assertThat(requests.getAllValues().get(2).messages())
                .anyMatch(message -> message.content() != null
                        && message.content().contains("DUPLICATE_FAILED_CALL"));
    }

    @Test
    void requiresGitReviewAfterASuccessfulCodingChangeBeforeAcceptingFinalText() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding patch = binding("node_patch", "fs.apply_patch");
        ResolvedToolBinding review = binding("node_review", "git.review");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("Applying the fix.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-patch", "node_patch", Map.of("path", "TaxCalculator.java"))), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The fix is complete.", null, null, "test"))
                .thenReturn(new ModelGateway.ModelAnswer("Reviewing the diff.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-review", "node_review", Map.of())), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Verified result.", null, null, "test"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), null, null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a", "model-a", List.of(patch, review),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Fix the calculator defect."))),
                actor, io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null));

        assertThat(answer).isEqualTo("Verified result.");
        verify(tools, times(2)).invoke(any());
        verify(gateway, times(4)).complete(any());
    }

    @Test
    void nodeInteractionCanDynamicallySelectTheDesktopListingTool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding desktopList = binding("node_desktop_list", "system.desktop.organize.list");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "I will inspect the desktop.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall("call-list", "node_desktop_list", Map.of())), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The desktop files are listed.", null, null, "test"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult(
                "SUCCEEDED", true, Map.of("sortableFiles", List.of("notes.txt")), null, null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-a", "model-a", List.of(desktopList),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "桌面有什么文件"))), actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.ON_REQUEST);

        assertThat(answer).isEqualTo("The desktop files are listed.");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(2)).complete(request.capture());
        assertThat(request.getAllValues().getFirst().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        verify(tools).invoke(any());
    }

    @Test
    void completesAStructuredPackageInstallWithoutAnotherModelTurn() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding install = binding("node_software_install", "system.software.install", true);
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("", null, null, "test",
                List.of(new ModelGateway.ModelToolCall(
                        "call-install", "node_software_install", Map.of("packageId", "jq"))), "tool_calls"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true,
                Map.of("packageId", "jq", "manager", "apt", "durationMs", 17, "exitCode", 0), null, null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-a", "model-a", List.of(install),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Install jq"))), actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("Package installation succeeded: package=jq, manager=apt, exitCode=0, durationMs=17");
        verify(gateway, times(1)).complete(any());
    }

    @Test
    void completesAStructuredPackageQueryWithoutAnotherModelTurn() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding query = binding("node_software_query", "system.software.query");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("", null, null, "test",
                List.of(new ModelGateway.ModelToolCall(
                        "call-query", "node_software_query", Map.of("packageId", "jq"))), "tool_calls"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true,
                Map.of("packageId", "jq", "manager", "apt", "installed", true,
                        "stdout", "install ok installed\t1.6\n", "durationMs", 12, "exitCode", 0), null, null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-a", "model-a", List.of(query),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Check jq"))), actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("Package query completed: package=jq, manager=apt, installed=true, version=1.6, exitCode=0, durationMs=12");
        verify(gateway, times(1)).complete(any());
    }

    @Test
    void acceptsAnUnambiguousRegeneratedModelToolDigest() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding read = binding("tool_fs_read_9d13a3775656", "fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("Reading.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-read", "fs_read_e4388cb2b79e", Map.of("path", "status.txt"))), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The file was read.", null, null, "test"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult(
                "SUCCEEDED", true, Map.of("content", "ready"), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-a", "model-a", List.of(read),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Read status.txt"))), actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("The file was read.");
        ArgumentCaptor<ToolInvocationRequest> invocation = ArgumentCaptor.forClass(ToolInvocationRequest.class);
        verify(tools).invoke(invocation.capture());
        assertThat(invocation.getValue().binding()).isEqualTo(read);
    }

    @Test
    void nonStreamingEmptyFinalTextFallsBackToTheCompletedToolResult() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        ResolvedToolBinding read = binding("tool_fs_read_9d13a3775656", "fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer("Reading.", null, null, "test",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-read", "tool_fs_read_9d13a3775656", Map.of("path", "status.txt"))), "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("", null, null, "test"))
                .thenReturn(new ModelGateway.ModelAnswer("The exact content is ready.", null, null, "test"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult(
                "SUCCEEDED", true, Map.of("content", "ready"), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, mock(RunEventPublisher.class)).executeInteraction(
                "run-a", "model-a", List.of(read),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Read status.txt"))), actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("The exact content is ready.");
    }

    @Test
    void cleanupTimeoutPublishesAWarningInsteadOfAToolCallFailure() {
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        when(tools.cleanup("run-a", actor)).thenReturn(List.of(
                new ToolCleanupResult("node", "node-a", "browser.close_session", false, "Invocation timed out")));

        new CodingAgentLoop(mock(ModelGateway.class), tools, events).cleanupManagedProcesses("run-a", actor);

        verify(events).publish(
                "run-a", RunEventType.RESOURCE_CLEANUP_WARNING,
                "tool=browser.close_session cleanup, error=Invocation timed out", actor);
        verify(events, never()).publish(
                eq("run-a"), eq(RunEventType.TOOL_CALL_FAILED), any(), eq(actor));
    }

    @Test
    void largeToolResultsRemainValidJsonWhenTheyAreBounded() throws Exception {
        CodingAgentLoop loop = new CodingAgentLoop(mock(ModelGateway.class), mock(ToolRouter.class), mock(RunEventPublisher.class));

        String serialized = loop.serializeToolResult(
                binding("node_tool_1", "fs.read"),
                new ToolProviderResult("SUCCEEDED", true, Map.of("stdout", "x".repeat(24_000)), "", null));
        var parsed = new ObjectMapper().readTree(serialized);

        assertThat(serialized.length()).isLessThanOrEqualTo(12_000);
        assertThat(parsed.get("truncated").asBoolean()).isTrue();
        assertThat(parsed.get("resultPreview").isTextual()).isTrue();
    }

    @Test
    void compactsOldToolOutputButPreservesTaskInstructionsAndRecentState() {
        List<ModelGateway.ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelGateway.ModelMessage("system", "System workflow"));
        messages.add(new ModelGateway.ModelMessage("user", "Fix the project"));
        for (int index = 0; index < 12; index++) {
            messages.add(ModelGateway.ModelMessage.toolResult("old-" + index, "x".repeat(10_000)));
        }
        messages.add(ModelGateway.ModelMessage.toolResult("recent", "recent verification result"));

        CodingAgentLoop.compactContextIfNeeded(messages);

        assertThat(messages).anyMatch(message -> "System workflow".equals(message.content()));
        assertThat(messages).anyMatch(message -> "Fix the project".equals(message.content()));
        assertThat(messages).anyMatch(message -> message.content() != null
                && message.content().contains("Earlier tool history was compacted")
                && message.content().contains("Removed outputs cannot support current facts or completion claims")
                && message.content().contains("available read-only tool")
                && message.content().contains("untrusted data"));
        assertThat(messages).noneMatch(message -> message.content() != null
                && message.content().contains("Use project.map"));
        assertThat(messages).anyMatch(message -> "recent verification result".equals(message.content()));
        assertThat(messages.stream().mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum())
                .isLessThan(60_000);
    }

    @Test
    void compactionPreservesTheLatestUserRequestInsteadOfAnOlderTask() {
        List<ModelGateway.ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelGateway.ModelMessage("system", "System workflow"));
        messages.add(new ModelGateway.ModelMessage("user", "Delete the old project"));
        for (int index = 0; index < 8; index++) {
            messages.add(ModelGateway.ModelMessage.toolResult("old-" + index, "x".repeat(10_000)));
        }
        messages.add(new ModelGateway.ModelMessage("user", "List the files on the Desktop"));
        messages.add(ModelGateway.ModelMessage.toolResult("recent", "recent verification result"));

        CodingAgentLoop.compactContextIfNeeded(messages);

        assertThat(messages).anyMatch(message -> "List the files on the Desktop".equals(message.content()));
        assertThat(messages).noneMatch(message -> "Delete the old project".equals(message.content()));
    }

    @Test
    void cancelledRunDoesNotAskTheModelOrInvokeTools() {
        ModelGateway modelGateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunExecutionRegistry executions = new RunExecutionRegistry();
        executions.cancel("run-cancelled");
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        CodingAgentLoop loop = new CodingAgentLoop(
                modelGateway, tools, mock(RunEventPublisher.class), executions, duration -> { });

        assertThatThrownBy(() -> loop.execute(
                "run-cancelled", "model-a", List.of(), new java.util.ArrayList<>(), actor))
                .hasMessageContaining("was cancelled");
        verifyNoInteractions(modelGateway);
    }

    @Test
    void acceptsAPlainTextResponseWhenTheModelChoosesNotToUseATool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("I will inspect it.", null, null, "test"));

        assertThat(new CodingAgentLoop(gateway, tools, events).execute(
                        "run-a", "model-a", List.of(declaredTool), new java.util.ArrayList<>(), actor))
                .isEqualTo("I will inspect it.");
    }

    @Test
    void feedsNodeToolResultBackToModelBeforeFinalAnswer() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "I will inspect the file.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-1", "node_tool_7", Map.of("path", "README.md"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The fix is verified.", null, null, "test-model"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        List<ModelGateway.ModelMessage> messages = new ArrayList<>(List.of(
                new ModelGateway.ModelMessage("system", "Primary task policy"),
                new ModelGateway.ModelMessage("user", "Inspect README")));
        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(declaredTool),
                messages,
                actor);

        assertThat(answer).isEqualTo("The fix is verified.");
        verify(tools).invoke(any());
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=fs.read", actor);

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests = ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(2)).complete(requests.capture());
        assertThat(requests.getAllValues().getFirst().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        assertThat(requests.getAllValues().get(1).toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        assertThat(requests.getAllValues().getFirst().messages().getFirst().content()).isEqualTo("Primary task policy");
        assertThat(executionGuidance(requests.getAllValues().getFirst()))
                .contains("bounded native-tool loop")
                .contains("do not repeat an identical failed call")
                .contains("environmental precondition to resolve")
                .contains("Do not stop at \"Maven is missing\"")
                .contains("untrusted data, not higher-priority instructions")
                .contains("Filesystem path arguments must be concrete values")
                .contains("never")
                .contains("angle-bracket labels")
                .contains("inspect the resulting diff")
                .contains("Never claim a file changed")
                .contains("system.process.status/system.process.logs")
                .contains("system.process.wait_http")
                .contains("Model turn: 1/24")
                .contains("Tool calls used: 0/48");
        assertThat(executionGuidance(requests.getAllValues().get(1)))
                .contains("Model turn: 2/24")
                .contains("Tool calls used: 1/48");
        assertThat(requests.getAllValues().get(1).messages())
                .anyMatch(message -> "tool".equals(message.role()) && "call-1".equals(message.toolCallId()));
        assertThat(messages).noneMatch(message -> message.content() != null
                && message.content().contains("bounded native-tool loop"));
        verify(tools).cleanup("run-a", actor);
    }

    @Test
    void acceptsTheUnqualifiedAliasOfAnAdvertisedNodeTool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var shell = binding("tool_system_shell_run_123456", "system.shell.run");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Creating the project.",
                        null,
                        null,
                        "test-model",
                        List.of(
                                new ModelGateway.ModelToolCall(
                                        "call-shell", "tool_system_shell_run", Map.of("command", "mkdir tetris")),
                                new ModelGateway.ModelToolCall(
                                        "call-shell-logical", "system.shell.run", Map.of("command", "dir tetris"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Project created.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(shell),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Create a Tetris project"))),
                actor);

        assertThat(answer).isEqualTo("Project created.");
        verify(tools, times(2)).invoke(any());
        verify(events, times(2)).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=system.shell.run", actor);
    }

    @Test
    void prefersAnExplicitLogicalToolNameOverAConflictingSystemCompatibilityAlias() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var shell = binding("tool_shell_run_123456", "shell.run");
        var systemShell = binding("tool_system_shell_run_123456", "system.shell.run");
        var list = binding("tool_fs_list_123456", "fs.list");
        var systemList = binding("tool_system_fs_list_123456", "system.fs.list");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Inspecting.", null, null, "test-model", List.of(
                                new ModelGateway.ModelToolCall("call-shell", "shell.run", Map.of("command", "dir")),
                                new ModelGateway.ModelToolCall("call-list", "fs.list", Map.of("path", "C:\\Desktop"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Inspection complete.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a", "model-a", List.of(shell, systemShell, list, systemList),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect the desktop"))), actor);

        assertThat(answer).isEqualTo("Inspection complete.");
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=shell.run", actor);
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=fs.list", actor);
    }

    @Test
    void removesNullToolArgumentsBeforeInvokingTheRouter() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var shell = binding("tool_system_shell_run_123456", "system.shell.run");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", "pwd");
        arguments.put("cwd", null);
        arguments.put("timeoutSeconds", null);
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Checking the current directory.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-shell", "system.shell.run", arguments)),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Directory checked.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(shell),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Check the cwd"))),
                actor);

        assertThat(answer).isEqualTo("Directory checked.");
        ArgumentCaptor<ToolInvocationRequest> request = ArgumentCaptor.forClass(ToolInvocationRequest.class);
        verify(tools).invoke(request.capture());
        assertThat(request.getValue().arguments())
                .containsEntry("command", "pwd")
                .doesNotContainKeys("cwd", "timeoutSeconds");
    }

    @Test
    void acceptsTheUnqualifiedAliasOfAnAdvertisedManagedProcessTool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var process = binding("tool_process_start_123456", "process.start");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Starting the process.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-process", "tool_process_start", Map.of("command", "run"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The process was started.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(process),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Start the server"))),
                actor);

        assertThat(answer).isEqualTo("The process was started.");
        verify(tools).invoke(any());
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=process.start", actor);
    }

    @Test
    void acceptsTheUnqualifiedAliasOfAnAdvertisedSystemManagedProcessTool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var process = binding("tool_system_process_start_123456", "system.process.start");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Starting the system process.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-process", "tool_system_process_start", Map.of("command", "run"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The system process was started.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(process),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Start the desktop app server"))),
                actor);

        assertThat(answer).isEqualTo("The system process was started.");
        verify(tools).invoke(any());
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=system.process.start", actor);
    }

    @Test
    void acceptsWorkspaceStyleAliasOfAnAdvertisedSystemManagedProcessTool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var process = binding("tool_system_process_start_123456", "system.process.start");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Starting the system process.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-process", "tool_process_start", Map.of("command", "run"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The system process was started.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(process),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Start the desktop app server"))),
                actor);

        assertThat(answer).isEqualTo("The system process was started.");
        verify(tools).invoke(any());
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=system.process.start", actor);
    }

    @Test
    void resolvesWorkspaceStyleAliasToTheExplicitlyAdvertisedWorkspaceTool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var workspaceProcess = binding("tool_process_start_123456", "process.start");
        var systemProcess = binding("tool_system_process_start_123456", "system.process.start");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Starting the workspace process.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-process", "tool_process_start", Map.of("command", "run"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The system process was started.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(workspaceProcess, systemProcess),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Start the desktop app server"))),
                actor);

        assertThat(answer).isEqualTo("The system process was started.");
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=process.start", actor);
    }

    @Test
    void rejectsAnUnqualifiedAliasThatWasNotAdvertisedForThisRun() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var fileRead = binding("tool_system_fs_read_123456", "system.fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Trying shell.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-shell", "tool_system_shell_run", Map.of())),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Reading the file.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-read", "tool_system_fs_read_123456", Map.of("path", "README.md"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Inspection complete.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(fileRead),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("Inspection complete.");
        verify(events).publish(
                "run-a", RunEventType.TOOL_CALL_FAILED, "Unknown tool requested: tool_system_shell_run", actor);
        verify(tools, times(1)).invoke(any());
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(3)).complete(requests.capture());
        String unknownToolResult = requests.getAllValues().get(1).messages().stream()
                .filter(message -> "call-shell".equals(message.toolCallId()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(unknownToolResult)
                .contains("\"availableTools\":[\"system.fs.read\"]")
                .contains("Choose one of availableTools exactly")
                .doesNotContain("system.shell.run");
    }

    @Test
    void streamsOnlyTheFinalDeliveryAfterToolTurnsComplete() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_stream", "fs.read");
        when(gateway.supportsStreaming()).thenReturn(true);
        when(gateway.stream(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken = invocation.getArgument(1);
            int turn = invocation.getArgument(0, ModelGateway.ModelCompletionRequest.class)
                    .messages().stream()
                    .mapToInt(message -> message.toolCalls() == null ? 0 : message.toolCalls().size())
                    .sum();
            boolean finalDelivery = invocation.getArgument(0, ModelGateway.ModelCompletionRequest.class)
                    .tools().isEmpty();
            if (turn == 0) {
                onToken.accept("I will inspect the file first.");
                return new ModelGateway.ModelAnswer(
                        "I will inspect the file first.",
                        null,
                        null,
                        "stream-model",
                        List.of(new ModelGateway.ModelToolCall(
                                "stream-call-1", "node_tool_stream", Map.of("path", "README.md"))),
                        "tool_calls");
            }
            if (finalDelivery) {
                onToken.accept("The file ");
                onToken.accept("was verified.");
                return new ModelGateway.ModelAnswer("The file was verified.", null, null, "stream-model");
            }
            onToken.accept("The file was verified.");
            return new ModelGateway.ModelAnswer("The file was verified.", null, null, "stream-model");
        });
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-stream", actor)).thenReturn(List.of());

        CodingAgentLoop loop = new CodingAgentLoop(gateway, tools, events);
        String answer = loop.execute(
                "run-stream",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("The file was verified.");
        verify(gateway, never()).complete(any());
        verify(gateway, times(3)).stream(any(), any());
        verify(tools).invoke(any());
        verify(events).publish("run-stream", RunEventType.TOKEN_DELTA, "The file was verified.", actor);
        verify(events, never()).publish(
                eq("run-stream"), eq(RunEventType.TOKEN_DELTA), eq("I will inspect the file first."), eq(actor));
        assertThat(loop.consumeFinalAnswerStreamed("run-stream")).isTrue();
        assertThat(loop.consumeFinalAnswerStreamed("run-stream")).isFalse();
    }

    @Test
    void retriesAStreamingModelRateLimitBeforeExecutingTools() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_stream", "fs.read");
        when(gateway.supportsStreaming()).thenReturn(true);
        when(gateway.stream(any(), any()))
                .thenThrow(new ModelRateLimitException("provider limited", Duration.ofSeconds(2), null))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Verified after streaming retry.", null, null, "stream-model"));
        when(tools.cleanup("run-stream", actor)).thenReturn(List.of());
        List<Duration> delays = new ArrayList<>();

        String answer = new CodingAgentLoop(gateway, tools, events, delays::add).resume(
                "run-stream",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Continue"))),
                actor);

        assertThat(answer).isEqualTo("Verified after streaming retry.");
        assertThat(delays).containsExactly(Duration.ofSeconds(2));
        verify(gateway, never()).complete(any());
        verify(gateway, times(2)).stream(any(), any());
        verify(events).publish("run-stream", RunEventType.MODEL_RATE_LIMITED, "retry=1, delaySeconds=2", actor);
        verify(tools, never()).invoke(any());
    }

    @Test
    void retriesAStreamingModelGatewayFailureBeforeExecutingTools() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_stream", "fs.read");
        when(gateway.supportsStreaming()).thenReturn(true);
        when(gateway.stream(any(), any()))
                .thenThrow(new ModelTransientException("provider unavailable", 502, null))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Verified after gateway recovery.", null, null, "stream-model"));
        when(tools.cleanup("run-transient", actor)).thenReturn(List.of());
        List<Duration> delays = new ArrayList<>();

        String answer = new CodingAgentLoop(gateway, tools, events, delays::add).resume(
                "run-transient",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Continue"))),
                actor);

        assertThat(answer).isEqualTo("Verified after gateway recovery.");
        assertThat(delays).containsExactly(Duration.ofSeconds(1));
        verify(gateway, never()).complete(any());
        verify(gateway, times(2)).stream(any(), any());
        verify(events).publish(
                "run-transient", RunEventType.MODEL_PROVIDER_RETRYING,
                "retry=1, status=502, delaySeconds=1", actor);
        verify(tools, never()).invoke(any());
    }

    @Test
    void returnsActionableStructuredFeedbackForAnUnknownTool() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Trying an unavailable tool.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-missing", "missing_tool", Map.of())),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Recovering with an advertised tool.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall(
                                "call-read", "node_tool_7", Map.of("path", "README.md"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Inspection complete.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("Inspection complete.");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(3)).complete(requests.capture());
        assertThat(requests.getAllValues().get(1).messages())
                .filteredOn(message -> "call-missing".equals(message.toolCallId()))
                .extracting(ModelGateway.ModelMessage::content)
                .singleElement()
                .asString()
                .contains("\"code\":\"TOOL_NOT_AVAILABLE\"")
                .contains("do not retry this tool name");
        verify(tools, times(1)).invoke(any());
    }

    @Test
    void retriesProviderRateLimitsWithoutRepeatingToolExecution() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any()))
                .thenThrow(new ModelRateLimitException("provider limited", Duration.ofSeconds(2), null))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Inspecting.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-1", "node_tool_7", Map.of("path", "README.md"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Verified after retry.", null, null, "test-model"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        List<Duration> delays = new ArrayList<>();

        String answer = new CodingAgentLoop(gateway, tools, events, delays::add).execute(
                "run-a",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("Verified after retry.");
        assertThat(delays).containsExactly(Duration.ofSeconds(2));
        verify(events).publish("run-a", RunEventType.MODEL_RATE_LIMITED, "retry=1, delaySeconds=2", actor);
        verify(tools, times(1)).invoke(any());
    }

    @Test
    void pausesForApprovalWithoutCleaningUpManagedProcesses() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_9", "process.start", true);
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "I need to start the server.",
                null,
                null,
                "test-model",
                List.of(
                        new ModelGateway.ModelToolCall(
                                "call-approval", "node_tool_9", Map.of("command", "java App")),
                        new ModelGateway.ModelToolCall(
                                "call-deferred", "node_tool_9", Map.of("command", "check App"))),
                "tool_calls"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult(
                        "APPROVAL_REQUIRED", false, Map.of(), "", "approval-1"));

        List<ModelGateway.ModelMessage> messages = new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Start it")));
        assertThatThrownBy(() -> new CodingAgentLoop(gateway, tools, events).execute(
                        "run-a", "model-a", List.of(declaredTool), messages, actor))
                .isInstanceOfSatisfying(CodingApprovalRequiredException.class, exception -> {
                    assertThat(exception.approvalId()).isEqualTo("approval-1");
                    assertThat(exception.toolCallId()).isEqualTo("call-approval");
                    assertThat(exception.messages()).anyMatch(message ->
                            "assistant".equals(message.role()) && !message.toolCalls().isEmpty());
                    assertThat(exception.messages())
                            .filteredOn(message -> "call-deferred".equals(message.toolCallId()))
                            .extracting(ModelGateway.ModelMessage::content)
                            .singleElement()
                            .asString()
                            .contains("\"code\":\"APPROVAL_PENDING\"")
                            .contains("this call did not run")
                            .contains("only if it is still needed");
                });

        verify(events).publish(
                "run-a",
                RunEventType.TOOL_APPROVAL_REQUIRED,
                "tool=process.start, approvalId=approval-1",
                actor);
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().tools().getFirst().description())
                .contains("Host logical operation: process.start")
                .contains("Calling it only requests approval")
                .contains("until a later tool result reports SUCCEEDED");
        verify(tools, never()).cleanup("run-a", actor);
    }

    @Test
    void resumedRunMayReturnFinalAnswerWithoutAnotherToolCall() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("The approved command is complete.", null, null, "test"));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).resume(
                "run-a",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(
                        new ModelGateway.ModelMessage("user", "Start it"),
                        ModelGateway.ModelMessage.toolResult("call-approval", "{\"status\":\"SUCCEEDED\"}"))),
                actor);

        assertThat(answer).isEqualTo("The approved command is complete.");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request = ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
    }

    @Test
    void reflectsTheRunApprovalModeInToolGuidanceAndDescriptions() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_10", "process.start", true);
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Starting it.", null, null, "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-1", "node_tool_10", Map.of("command", "run"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Started and verified.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a", "model-a", List.of(declaredTool),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Start it"))),
                actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null),
                io.github.yourname.cycbercompany.tool.ApprovalMode.FULL_ACCESS);

        assertThat(answer).isEqualTo("Started and verified.");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(2)).complete(requests.capture());
        assertThat(requests.getAllValues().getFirst().tools().getFirst().description())
                .contains("permits this call without a separate pause")
                .doesNotContain("requires human approval");
        assertThat(executionGuidance(requests.getAllValues().getFirst()))
                .contains("Approval mode: full-access")
                .contains("never expands the advertised tool set");
    }

    @Test
    void codingRunDoesNotOverrideTheModelToolChoiceFromTextKeywords() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var move = binding("node_move", "system.desktop.organize.move");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "Moving files.",
                null,
                null,
                "test-model",
                List.of(new ModelGateway.ModelToolCall("call-move", "node_move", Map.of(
                        "source", "a.txt", "category", "Documents"))),
                "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Desktop move is complete.", null, null, "test-model"));
        when(tools.invoke(any())).thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(move),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Organize my desktop"))),
                actor,
                io.github.yourname.cycbercompany.tool.CodingWorkspaceScope.from(null));

        assertThat(answer).isEqualTo("Desktop move is complete.");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(2)).complete(requests.capture());
        assertThat(executionGuidance(requests.getAllValues().getFirst()))
                .doesNotContain("Required first logical operation");
        verify(tools, times(1)).invoke(any());
    }

    private static String executionGuidance(ModelGateway.ModelCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(ModelGateway.ModelMessage::content)
                .filter(content -> content != null && (content.contains("bounded native-tool loop")
                        || content.contains("Complete the user's request directly.")))
                .findFirst()
                .orElseThrow();
    }

    private static ResolvedToolBinding binding(String modelName, String logicalName) {
        return binding(modelName, logicalName, false);
    }

    private static ResolvedToolBinding binding(String modelName, String logicalName, boolean requiresApproval) {
        return binding(modelName, logicalName, requiresApproval, "node");
    }

    private static ResolvedToolBinding binding(
            String modelName, String logicalName, boolean requiresApproval, String providerId) {
        return new ResolvedToolBinding(
                providerId + ":node-a:" + logicalName,
                modelName,
                logicalName,
                providerId,
                logicalName,
                "Node tool " + logicalName,
                RiskLevel.LOW,
                requiresApproval,
                Map.of("type", "object"),
                Map.of("nodeId", "node-a"));
    }
}
