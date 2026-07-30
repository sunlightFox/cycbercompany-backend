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

    private static final String DEFAULT_ASSISTANT_ID = "default-assistant";
    private static final String DEFAULT_ASSISTANT_TOOLS = "local_time,knowledge_search,web_search";
    private static final String DEFAULT_ASSISTANT_PROMPT = """
            You are Spring Agent Studio's default assistant.

            Runtime capabilities available through the backend:
            - local_time: read the server's current time.
            - knowledge_search: search tenant-scoped local knowledge bases when the user provides or selects them.
            - web_search: search the public web for current or external information.

            Answer clearly. When web or knowledge evidence is retrieved, use only relevant evidence and cite source URLs
            or knowledge references when they materially support a claim. If evidence is missing or inconclusive, say so.
            """;

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

        var defaultAssistant = agents.findById(DEFAULT_ASSISTANT_ID);
        if (defaultAssistant.isEmpty()) {
            agents.save(new AgentDefinitionEntity(
                    DEFAULT_ASSISTANT_ID,
                    "Default Assistant",
                    "A careful single-agent assistant for local demos.",
                    DEFAULT_ASSISTANT_PROMPT,
                    defaultModelProfileId,
                    DEFAULT_ASSISTANT_TOOLS,
                    true,
                    Instant.now()));
        } else if (isLegacyDefaultAssistant(defaultAssistant.get())) {
            AgentDefinitionEntity assistant = defaultAssistant.get();
            assistant.updateRuntimeDefaults(DEFAULT_ASSISTANT_PROMPT, DEFAULT_ASSISTANT_TOOLS);
            agents.save(assistant);
        }
    }

    private static boolean isLegacyDefaultAssistant(AgentDefinitionEntity assistant) {
        return assistant.toolAllowList() == null
                || !assistant.toolAllowList().contains("web_search")
                || assistant.systemPrompt() == null
                || !assistant.systemPrompt().contains("web_search");
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
