package io.github.yourname.agentstudio.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentCatalogTest {

    @Test
    void createsAnEmployeeWithItsBasicInfoAndTheDefaultRuntimeConfiguration() {
        AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
        AgentDefinitionEntity defaultAgent = agent(
                "default-assistant", "Default Assistant", "platform default", "platform persona");
        when(repository.findById("default-assistant")).thenReturn(Optional.of(defaultAgent));
        when(repository.save(any(AgentDefinitionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentDefinitionView created = new AgentCatalog(repository).create(
                new CreateAgentCommand("  Research partner  ", "  Finds reliable answers.  ", "  Be careful and concise.  "));

        ArgumentCaptor<AgentDefinitionEntity> saved = ArgumentCaptor.forClass(AgentDefinitionEntity.class);
        verify(repository).save(saved.capture());
        assertThat(created.id()).isNotBlank().isNotEqualTo("default-assistant");
        assertThat(created.name()).isEqualTo("Research partner");
        assertThat(created.description()).isEqualTo("Finds reliable answers.");
        assertThat(created.systemPrompt()).isEqualTo("Be careful and concise.");
        assertThat(saved.getValue().defaultModelProfileId()).isEqualTo("model-default");
        assertThat(saved.getValue().toolAllowList()).isEqualTo("knowledge_search");
        assertThat(created.enabled()).isTrue();
    }

    @Test
    void updatesBasicInfoAndAnExplicitDefaultModelOverride() {
        AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
        AgentDefinitionEntity existing = agent("employee-1", "Old name", "Old description", "Old persona");
        when(repository.findById("employee-1")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        AgentDefinitionView updated = new AgentCatalog(repository).update(
                "employee-1", new UpdateAgentCommand(
                        "  New name ", "  New description ", "  New persona ", " model-review "));

        verify(repository).save(existing);
        assertThat(updated.name()).isEqualTo("New name");
        assertThat(updated.description()).isEqualTo("New description");
        assertThat(updated.systemPrompt()).isEqualTo("New persona");
        assertThat(existing.defaultModelProfileId()).isEqualTo("model-review");
        assertThat(existing.toolAllowList()).isEqualTo("knowledge_search");
    }

    @Test
    void clearsTheAgentModelOverrideWhenTheEditorChoosesTheGlobalDefault() {
        AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
        AgentDefinitionEntity existing = agent("employee-1", "Old name", "Old description", "Old persona");
        when(repository.findById("employee-1")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        new AgentCatalog(repository).update(
                "employee-1", new UpdateAgentCommand("New name", "New description", "New persona", ""));

        assertThat(existing.defaultModelProfileId()).isNull();
    }

    private static AgentDefinitionEntity agent(String id, String name, String description, String prompt) {
        return new AgentDefinitionEntity(
                id,
                name,
                description,
                prompt,
                "model-default",
                "knowledge_search",
                true,
                Instant.now());
    }
}
