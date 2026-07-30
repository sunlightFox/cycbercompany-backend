package io.github.yourname.agentstudio.model;

import java.util.Set;

public record ModelProfileView(
        String id,
        ProviderType providerType,
        String baseUrl,
        String modelName,
        String credentialRef,
        boolean apiKeyConfigured,
        String apiKeyPreview,
        Set<ModelCapability> capabilities,
        boolean enabled,
        boolean defaultProfile) {

    public static ModelProfileView from(ModelProfileEntity entity) {
        return from(entity, false);
    }

    public static ModelProfileView from(ModelProfileEntity entity, boolean defaultProfile) {
        return new ModelProfileView(
                entity.id(),
                entity.providerType(),
                entity.baseUrl(),
                entity.modelName(),
                entity.credentialRef(),
                entity.apiKey() != null && !entity.apiKey().isBlank(),
                maskKey(entity.apiKey()),
                entity.capabilities(),
                entity.enabled(),
                defaultProfile);
    }

    private static String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
