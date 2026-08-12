package io.github.yourname.agentstudio.mod;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First local registry; remote marketplace providers can implement the same view later. */
@Service
public class ModRegistryService {

    private final Map<String, ModManifestView> manifests;
    private final ModInstallationRepository installations;

    public ModRegistryService(ModInstallationRepository installations, List<ModProvider> providers) {
        this.installations = installations;
        this.manifests = providers.stream()
                .map(ModProvider::manifest)
                .filter(manifest -> manifest != null && manifest.id() != null && !manifest.id().isBlank())
                .collect(Collectors.toUnmodifiableMap(ModManifestView::id, Function.identity(), (first, ignored) -> first));
    }

    public List<ModManifestView> list(ActorContext actor) {
        return manifests.values().stream().map(mod -> withInstallState(mod, actor)).toList();
    }

    public ModManifestView get(String id, ActorContext actor) {
        ModManifestView manifest = manifests.get(id);
        if (manifest == null) {
            throw new IllegalArgumentException("Mod not found: " + id);
        }
        return withInstallState(manifest, actor);
    }

    public boolean isInstalled(String id, ActorContext actor) {
        return installations.existsByTenantIdAndUserIdAndModId(actor.tenantId(), actor.userId(), id);
    }

    @Transactional
    public ModManifestView install(String id, ActorContext actor) {
        ModManifestView manifest = manifests.get(id);
        if (manifest == null) {
            throw new IllegalArgumentException("Mod not found: " + id);
        }
        if (!isInstalled(id, actor)) {
            installations.save(new ModInstallationEntity("mod_install_" + UUID.randomUUID(), actor.tenantId(), actor.userId(), id, Instant.now()));
        }
        return withInstallState(manifest, actor);
    }

    private ModManifestView withInstallState(ModManifestView mod, ActorContext actor) {
        return new ModManifestView(mod.id(), mod.name(), mod.version(), mod.description(), mod.category(),
                isInstalled(mod.id(), actor), mod.surfaces(), mod.capabilities(), mod.memorySchemas(),
                mod.permissions(), mod.sourceAdapters());
    }

}
