package io.github.yourname.cycbercompany.model;

import java.util.Set;

/**
 * Ready-to-use provider/model template for the UI.
 *
 * <p>Presets are not secrets and do not create a profile by themselves. The UI
 * can copy a preset into an {@link UpsertModelProfileCommand}, let the user add
 * an API key or credential env var, then save it as a normal profile.
 */
public record ModelPresetView(
        String id,
        String providerName,
        String displayName,
        ProviderType providerType,
        String baseUrl,
        String modelName,
        String credentialRef,
        Set<ModelCapability> capabilities,
        String notes) {
}
