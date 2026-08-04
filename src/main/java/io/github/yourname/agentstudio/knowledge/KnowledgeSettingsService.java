package io.github.yourname.agentstudio.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.model.ModelCapability;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.model.ModelProfileEntity;
import io.github.yourname.agentstudio.model.ModelProfileRepository;
import io.github.yourname.agentstudio.model.ProviderType;
import io.github.yourname.agentstudio.model.UpsertModelProfileCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the settings that drive the local RAG pipeline.
 *
 * <p>The provider credential remains on a model profile. This settings file stores only the
 * reference to that profile and non-sensitive indexing parameters.
 */
@Service
public class KnowledgeSettingsService {

    static final String EMBEDDING_PROFILE_ID = "knowledge-embedding";
    private static final String SETTINGS_FILE = "knowledge-settings.json";
    private static final String LOCAL_VECTOR_STORE = "local";
    private static final int DEFAULT_CHUNK_SIZE = 4_000;
    private static final int DEFAULT_CHUNK_OVERLAP = 1_500;

    private final AppProperties properties;
    private final ModelCatalog models;
    private final ModelProfileRepository profiles;
    private final ObjectMapper objectMapper;

    public KnowledgeSettingsService(
            AppProperties properties,
            ModelCatalog models,
            ModelProfileRepository profiles,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.models = models;
        this.profiles = profiles;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public KnowledgeSettingsView view() {
        return toView(read().orElseGet(this::defaults));
    }

    @Transactional
    public KnowledgeSettingsView update(UpdateKnowledgeSettingsCommand command) {
        if (command.chunkOverlap() >= command.chunkSize()) {
            throw new IllegalArgumentException("Chunk overlap must be smaller than chunk size.");
        }
        if (!LOCAL_VECTOR_STORE.equalsIgnoreCase(command.vectorStore().trim())) {
            throw new IllegalArgumentException("Only the local vector store is available in this deployment.");
        }

        StoredKnowledgeSettings current = read().orElseGet(this::defaults);
        String profileId = current.embeddingModelProfileId();
        ModelProfileEntity existing = profiles.findById(profileId).orElse(null);
        String model = valueOrExisting(command.embeddingModel(), existing == null ? null : existing.modelName());
        String baseUrl = valueOrExisting(command.embeddingBaseUrl(), existing == null ? null : existing.baseUrl());
        String credentialEnv = valueOrExisting(
                command.embeddingCredentialEnv(),
                existing == null ? null : existing.credentialRef());

        if (command.embeddingEnabled() && (model.isBlank() || baseUrl.isBlank())) {
            throw new IllegalArgumentException("Embedding model and base URL are required when embeddings are enabled.");
        }
        if (!model.isBlank() || !baseUrl.isBlank() || existing != null) {
            if (model.isBlank() || baseUrl.isBlank()) {
                throw new IllegalArgumentException("Embedding model and base URL must be configured together.");
            }
            models.save(new UpsertModelProfileCommand(
                    profileId,
                    ProviderType.OPENAI_COMPATIBLE,
                    baseUrl,
                    model,
                    credentialEnv,
                    blankToNull(command.apiKey()),
                    Set.of(ModelCapability.EMBEDDING),
                    true));
        }

        StoredKnowledgeSettings saved = new StoredKnowledgeSettings(
                command.embeddingEnabled(),
                profileId,
                LOCAL_VECTOR_STORE,
                command.chunkSize(),
                command.chunkOverlap());
        write(saved);
        return toView(saved);
    }

    public boolean embeddingEnabled() {
        return read().orElseGet(this::defaults).embeddingEnabled();
    }

    public String embeddingModelProfileId() {
        return read().orElseGet(this::defaults).embeddingModelProfileId();
    }

    public int chunkSize() {
        return read().orElseGet(this::defaults).chunkSize();
    }

    public int chunkOverlap() {
        return read().orElseGet(this::defaults).chunkOverlap();
    }

    private KnowledgeSettingsView toView(StoredKnowledgeSettings settings) {
        ModelProfileEntity profile = profiles.findById(settings.embeddingModelProfileId()).orElse(null);
        return new KnowledgeSettingsView(
                settings.embeddingEnabled(),
                credentialConfigured(profile),
                profile == null ? "" : profile.modelName(),
                profile == null ? "" : profile.baseUrl(),
                profile == null ? "" : nullToEmpty(profile.credentialRef()),
                settings.vectorStore(),
                settings.chunkSize(),
                settings.chunkOverlap());
    }

    private StoredKnowledgeSettings defaults() {
        AppProperties.Rag rag = properties.rag();
        return new StoredKnowledgeSettings(
                rag != null && rag.embeddingEnabled(),
                rag == null || blank(rag.embeddingModelProfileId())
                        ? EMBEDDING_PROFILE_ID
                        : rag.embeddingModelProfileId().trim(),
                LOCAL_VECTOR_STORE,
                DEFAULT_CHUNK_SIZE,
                DEFAULT_CHUNK_OVERLAP);
    }

    private Optional<StoredKnowledgeSettings> read() {
        Path file = settingsFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), StoredKnowledgeSettings.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read knowledge settings: " + ex.getMessage(), ex);
        }
    }

    private void write(StoredKnowledgeSettings settings) {
        try {
            Files.createDirectories(settingsFile().getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile().toFile(), settings);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save knowledge settings: " + ex.getMessage(), ex);
        }
    }

    private Path settingsFile() {
        Path dataDir = properties.dataDir() == null ? Path.of("./data") : properties.dataDir();
        return dataDir.resolve(SETTINGS_FILE).toAbsolutePath().normalize();
    }

    private static String valueOrExisting(String incoming, String existing) {
        String value = incoming == null ? "" : incoming.trim();
        return value.isEmpty() ? nullToEmpty(existing) : value;
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean credentialConfigured(ModelProfileEntity profile) {
        if (profile == null) {
            return false;
        }
        if (!blank(profile.apiKey())) {
            return true;
        }
        return !blank(profile.credentialRef()) && !blank(System.getenv(profile.credentialRef()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record StoredKnowledgeSettings(
            boolean embeddingEnabled,
            String embeddingModelProfileId,
            String vectorStore,
            int chunkSize,
            int chunkOverlap) {
    }
}
