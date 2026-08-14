package io.github.yourname.cycbercompany.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.model.ModelCapability;
import io.github.yourname.cycbercompany.model.ModelCatalog;
import io.github.yourname.cycbercompany.model.ModelGateway;
import io.github.yourname.cycbercompany.model.ModelProfileView;
import io.github.yourname.cycbercompany.model.ProviderType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentDraftTestServiceTest {

    private final AgentIdentityRepository identities = mock(AgentIdentityRepository.class);
    private final AgentVersionRepository versions = mock(AgentVersionRepository.class);
    private final ModelCatalog models = mock(ModelCatalog.class);
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentManifestCompiler compiler = new AgentManifestCompiler(objectMapper);
    private final AgentDraftTestService service = new AgentDraftTestService(
            identities, versions, compiler, models, gateway, objectMapper);

    @BeforeEach
    void setUpDraft() {
        var identity = new AgentIdentityEntity(
                "agent-1", "tenant-1", "owner-1", "Reviewer", "", "", "", "[]", "PRIVATE", Instant.now());
        var compiled = compiler.compile(AgentManifestTestData.valid(objectMapper));
        var draft = new AgentVersionEntity(
                "draft-1", "agent-1", "tenant-1", 1, compiled, "owner-1", Instant.now());
        when(identities.findByIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(identity));
        when(versions.findByIdAndAgentIdAndTenantId("draft-1", "agent-1", "tenant-1"))
                .thenReturn(Optional.of(draft));
        when(models.get("model-review")).thenReturn(new ModelProfileView(
                "model-review",
                ProviderType.OPENAI_COMPATIBLE,
                "https://example.invalid/v1",
                "review-model",
                "",
                true,
                "***",
                Set.of(ModelCapability.TEXT, ModelCapability.TOOLS),
                true,
                false));
    }

    @Test
    void previewIsStatelessAndNeverExposesToolsToTheModel() {
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "Preview response",
                20,
                4,
                "review-model",
                List.of(new ModelGateway.ModelToolCall("call-1", "git.diff", Map.of())),
                "tool_calls"));

        AgentDraftTestView result = service.test(
                "agent-1",
                "draft-1",
                new AgentDraftTestCommand(List.of(
                        new AgentDraftTestMessage("USER", "Review this change"),
                        new AgentDraftTestMessage("ASSISTANT", "What should I focus on?"),
                        new AgentDraftTestMessage("USER", "Correctness")), null),
                "tenant-1",
                "owner-1");

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().tools()).isEmpty();
        assertThat(request.getValue().messages())
                .extracting(ModelGateway.ModelMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(request.getValue().messages().getFirst().content())
                .contains("Draft preview sandbox")
                .contains("No tools, Skills, MCP connections");
        assertThat(result.content()).isEqualTo("Preview response");
        assertThat(result.toolCallsBlocked()).isTrue();
        assertThat(result.notices()).singleElement().asString().contains("never executes tool calls");
        verify(identities, never()).save(any());
        verify(versions, never()).save(any());
    }

    @Test
    void previewRejectsNonOwnerBeforeCallingTheModel() {
        assertThatThrownBy(() -> service.test(
                        "agent-1",
                        "draft-1",
                        new AgentDraftTestCommand(
                                List.of(new AgentDraftTestMessage("USER", "Hello")), null),
                        "tenant-1",
                        "other-user"))
                .hasMessageContaining("Only the Agent owner");
        verify(gateway, never()).complete(any());
    }
}
