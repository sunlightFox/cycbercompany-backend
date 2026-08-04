package io.github.yourname.agentstudio.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.agent.AgentDefinitionEntity;
import io.github.yourname.agentstudio.agent.AgentDefinitionRepository;
import io.github.yourname.agentstudio.model.ModelCapability;
import io.github.yourname.agentstudio.model.ModelProfileEntity;
import io.github.yourname.agentstudio.model.ModelProfileRepository;
import io.github.yourname.agentstudio.model.ProviderType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

class DataSeederTest {

    @Test
    void addsToolsToExistingDefaultProfileWithoutReplacingItsConfiguration() throws Exception {
        ModelProfileRepository models = mock(ModelProfileRepository.class);
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        ModelProfileEntity existing = new ModelProfileEntity(
                "minimax-m3",
                ProviderType.OPENAI_COMPATIBLE,
                "https://custom.example/v1",
                "custom-model",
                "CUSTOM_KEY",
                "saved-key",
                EnumSet.of(ModelCapability.TEXT, ModelCapability.JSON_OUTPUT),
                true,
                Instant.now());
        when(models.findById("minimax-m3")).thenReturn(Optional.of(existing));
        when(agents.findById(any())).thenReturn(Optional.empty());

        new DataSeeder(properties(), models, agents).run(new DefaultApplicationArguments());

        ArgumentCaptor<ModelProfileEntity> saved = ArgumentCaptor.forClass(ModelProfileEntity.class);
        verify(models).save(saved.capture());
        assertThat(saved.getValue().capabilities()).contains(ModelCapability.TOOLS);
        assertThat(saved.getValue().baseUrl()).isEqualTo("https://custom.example/v1");
        assertThat(saved.getValue().modelName()).isEqualTo("custom-model");
        assertThat(saved.getValue().credentialRef()).isEqualTo("CUSTOM_KEY");
        assertThat(saved.getValue().apiKey()).isEqualTo("saved-key");
    }

    @Test
    void upgradesOnlyTheExactLegacyPlatformAssistantEvenWhenNodeToolsWereAlreadyAdded() throws Exception {
        ModelProfileRepository models = mock(ModelProfileRepository.class);
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        when(models.findById("minimax-m3")).thenReturn(Optional.of(configuredProfile()));
        AgentDefinitionEntity legacy = new AgentDefinitionEntity(
                DataSeeder.DEFAULT_ASSISTANT_ID,
                "Default Assistant",
                "platform default",
                DataSeeder.LEGACY_DEFAULT_ASSISTANT_PROMPT,
                "minimax-m3",
                DataSeeder.PREVIOUS_DEFAULT_ASSISTANT_TOOLS,
                true,
                Instant.now());
        when(agents.findById(DataSeeder.DEFAULT_ASSISTANT_ID)).thenReturn(Optional.of(legacy));

        new DataSeeder(properties(), models, agents).run(new DefaultApplicationArguments());

        verify(agents).save(legacy);
        assertThat(legacy.systemPrompt())
                .isEqualTo(DataSeeder.DEFAULT_ASSISTANT_PROMPT)
                .contains("only the capabilities authorized", "untrusted data", "what was verified",
                        "current run's approval mode", "successful tool result")
                .doesNotContain("required approval before it is saved");
        assertThat(legacy.toolAllowList()).isEqualTo(DataSeeder.DEFAULT_ASSISTANT_TOOLS);
    }

    @Test
    void upgradesTheOriginalTwoToolPlatformAssistantWithoutOverwritingCustomAgents() throws Exception {
        ModelProfileRepository models = mock(ModelProfileRepository.class);
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        when(models.findById("minimax-m3")).thenReturn(Optional.of(configuredProfile()));
        AgentDefinitionEntity original = new AgentDefinitionEntity(
                DataSeeder.DEFAULT_ASSISTANT_ID,
                "Default Assistant",
                "platform default",
                DataSeeder.INITIAL_DEFAULT_ASSISTANT_PROMPT,
                "minimax-m3",
                DataSeeder.INITIAL_DEFAULT_ASSISTANT_TOOLS,
                true,
                Instant.now());
        when(agents.findById(DataSeeder.DEFAULT_ASSISTANT_ID)).thenReturn(Optional.of(original));

        new DataSeeder(properties(), models, agents).run(new DefaultApplicationArguments());

        verify(agents).save(original);
        assertThat(original.systemPrompt()).isEqualTo(DataSeeder.DEFAULT_ASSISTANT_PROMPT);
        assertThat(original.toolAllowList()).isEqualTo(DataSeeder.DEFAULT_ASSISTANT_TOOLS);
    }

    @Test
    void preservesACustomPromptThatStillUsesThePlatformDefaultTools() throws Exception {
        ModelProfileRepository models = mock(ModelProfileRepository.class);
        AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
        when(models.findById("minimax-m3")).thenReturn(Optional.of(configuredProfile()));
        AgentDefinitionEntity customized = new AgentDefinitionEntity(
                DataSeeder.DEFAULT_ASSISTANT_ID,
                "Default Assistant",
                "customized",
                "My custom prompt must survive startup.",
                "minimax-m3",
                DataSeeder.DEFAULT_ASSISTANT_TOOLS,
                true,
                Instant.now());
        when(agents.findById(DataSeeder.DEFAULT_ASSISTANT_ID)).thenReturn(Optional.of(customized));

        new DataSeeder(properties(), models, agents).run(new DefaultApplicationArguments());

        verify(agents, never()).save(any(AgentDefinitionEntity.class));
        assertThat(customized.systemPrompt()).isEqualTo("My custom prompt must survive startup.");
    }

    private static ModelProfileEntity configuredProfile() {
        return new ModelProfileEntity(
                "minimax-m3",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.edgefn.net/v1",
                "MiniMax-M3",
                "EDGEFN_API_KEY",
                "saved-key",
                EnumSet.of(ModelCapability.TEXT, ModelCapability.JSON_OUTPUT, ModelCapability.TOOLS),
                true,
                Instant.now());
    }

    private static AppProperties properties() {
        return new AppProperties(
                null,
                new AppProperties.Ai(
                        "minimax-m3",
                        true,
                        new AppProperties.DefaultModelProfile(
                                "minimax-m3",
                                ProviderType.OPENAI_COMPATIBLE,
                                "https://api.edgefn.net/v1",
                                "MiniMax-M3",
                                "EDGEFN_API_KEY",
                                EnumSet.of(ModelCapability.TEXT, ModelCapability.JSON_OUTPUT, ModelCapability.TOOLS))),
                null,
                null,
                null,
                null,
                null);
    }
}
