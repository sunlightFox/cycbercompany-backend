package io.github.yourname.agentstudio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.model.ModelCapability;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.model.ModelProfileEntity;
import io.github.yourname.agentstudio.model.ModelProfileRepository;
import io.github.yourname.agentstudio.model.ProviderType;
import io.github.yourname.agentstudio.model.UpsertModelProfileCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class KnowledgeSettingsServiceTest {

    @TempDir
    Path dataDir;

    @Test
    void savesRuntimeSettingsAndConfiguresAnEmbeddingModelProfile() throws Exception {
        ModelCatalog models = mock(ModelCatalog.class);
        ModelProfileRepository profiles = mock(ModelProfileRepository.class);
        ModelProfileEntity profile = new ModelProfileEntity(
                KnowledgeSettingsService.EMBEDDING_PROFILE_ID,
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1",
                "text-embedding-3-small",
                "OPENAI_API_KEY",
                "sk-secret",
                Set.of(ModelCapability.EMBEDDING),
                true,
                Instant.now());
        when(profiles.findById(KnowledgeSettingsService.EMBEDDING_PROFILE_ID))
                .thenReturn(Optional.empty(), Optional.of(profile), Optional.of(profile));

        KnowledgeSettingsService service = new KnowledgeSettingsService(
                new AppProperties(dataDir, null, null, null, null, null, null),
                models,
                profiles,
                new ObjectMapper());

        KnowledgeSettingsView saved = service.update(new UpdateKnowledgeSettingsCommand(
                true,
                "text-embedding-3-small",
                "https://api.openai.com/v1",
                "OPENAI_API_KEY",
                "sk-secret",
                "local",
                800,
                120));

        ArgumentCaptor<UpsertModelProfileCommand> command = ArgumentCaptor.forClass(UpsertModelProfileCommand.class);
        verify(models).save(command.capture());
        assertThat(command.getValue().capabilities()).containsExactly(ModelCapability.EMBEDDING);
        assertThat(command.getValue().apiKey()).isEqualTo("sk-secret");
        assertThat(saved.embeddingCredentialConfigured()).isTrue();
        assertThat(saved.embeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(saved.chunkSize()).isEqualTo(800);
        assertThat(saved.chunkOverlap()).isEqualTo(120);
        assertThat(Files.readString(dataDir.resolve("knowledge-settings.json"))).doesNotContain("sk-secret");
    }
}
