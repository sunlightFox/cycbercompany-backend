package io.github.yourname.agentstudio.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
