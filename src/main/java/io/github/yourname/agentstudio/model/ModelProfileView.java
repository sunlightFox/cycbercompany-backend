package io.github.yourname.agentstudio.model;

import java.util.Set;

public record ModelProfileView(
        String id,
        ProviderType providerType,
        String baseUrl,
        String modelName,
        String credentialRef,
        Set<ModelCapability> capabilities,
        boolean enabled) {

    public static ModelProfileView from(ModelProfileEntity entity) {
        return new ModelProfileView(
                entity.id(),
                entity.providerType(),
                entity.baseUrl(),
                entity.modelName(),
                entity.credentialRef(),
                entity.capabilities(),
                entity.enabled());
    }
}
