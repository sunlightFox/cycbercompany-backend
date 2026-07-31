package io.github.yourname.agentstudio.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ModelProfileEntityTest {

    @Test
    void upgradesOnlyTheMissingCapabilityOnAnExistingProfile() {
        ModelProfileEntity profile = new ModelProfileEntity(
                "minimax-m3",
                ProviderType.OPENAI_COMPATIBLE,
                "https://configured.example/v1",
                "configured-model",
                "CONFIGURED_KEY",
                "stored-key",
                EnumSet.of(ModelCapability.TEXT, ModelCapability.JSON_OUTPUT),
                true,
                Instant.now());

        assertThat(profile.addMissingCapabilities(EnumSet.of(ModelCapability.TEXT, ModelCapability.TOOLS))).isTrue();
        assertThat(profile.capabilities()).containsExactlyInAnyOrder(
                ModelCapability.TEXT, ModelCapability.JSON_OUTPUT, ModelCapability.TOOLS);
        assertThat(profile.baseUrl()).isEqualTo("https://configured.example/v1");
        assertThat(profile.modelName()).isEqualTo("configured-model");
        assertThat(profile.credentialRef()).isEqualTo("CONFIGURED_KEY");
        assertThat(profile.apiKey()).isEqualTo("stored-key");
        assertThat(profile.addMissingCapabilities(EnumSet.of(ModelCapability.TEXT, ModelCapability.TOOLS))).isFalse();
    }
}
