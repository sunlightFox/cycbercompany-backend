package io.github.yourname.agentstudio.config;

import io.github.yourname.agentstudio.agent.AgentDefinitionEntity;
import io.github.yourname.agentstudio.agent.AgentDefinitionRepository;
import io.github.yourname.agentstudio.model.ModelCapability;
import io.github.yourname.agentstudio.model.ModelProfileEntity;
import io.github.yourname.agentstudio.model.ModelProfileRepository;
import io.github.yourname.agentstudio.model.ProviderType;
import java.time.Instant;
import java.util.EnumSet;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the local demo defaults once.
 *
 * <p>Keeping this in code rather than a migration makes the first learning pass
 * easier: the user can see exactly which runtime defaults exist and why they do
 * not contain any raw API keys.
 */
@Component
class DataSeeder implements ApplicationRunner {

    private final AppProperties properties;
    private final ModelProfileRepository modelProfiles;
    private final AgentDefinitionRepository agents;

    DataSeeder(AppProperties properties, ModelProfileRepository modelProfiles, AgentDefinitionRepository agents) {
        this.properties = properties;
        this.modelProfiles = modelProfiles;
        this.agents = agents;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Ai ai = properties.ai();
        AppProperties.DefaultModelProfile configured = ai == null ? fallbackProfile() : ai.defaultProfile();
        String defaultModelProfileId = ai == null ? configured.id() : ai.defaultModelProfileId();

        if ((ai == null || ai.seedDefaultProfile())
                && modelProfiles.findById(configured.id()).isEmpty()) {
            modelProfiles.save(new ModelProfileEntity(
                    configured.id(),
                    configured.providerType(),
                    configured.baseUrl(),
                    configured.modelName(),
                    configured.credentialRef(),
                    configured.capabilities(),
                    true,
                    Instant.now()));
        }

        if (agents.findById("default-assistant").isEmpty()) {
            agents.save(new AgentDefinitionEntity(
                    "default-assistant",
                    "Default Assistant",
                    "A careful single-agent assistant for local demos.",
                    """
                    You are Spring Agent Studio's default assistant.
                    Answer clearly, cite retrieved knowledge when available, and say when evidence is missing.
                    """,
                    defaultModelProfileId,
                    "local_time,knowledge_search",
                    true,
                    Instant.now()));
        }
    }

    private static AppProperties.DefaultModelProfile fallbackProfile() {
        return new AppProperties.DefaultModelProfile(
                "minimax-m3",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.edgefn.net/v1",
                "MiniMax-M3",
                "EDGEFN_API_KEY",
                EnumSet.of(ModelCapability.TEXT, ModelCapability.JSON_OUTPUT));
    }
}
