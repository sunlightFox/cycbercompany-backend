package io.github.yourname.cycbercompany.mod;

import io.github.yourname.cycbercompany.security.ActorContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * First runtime implementation for dynamic Mod surfaces. The state is intentionally
 * small and JSON-shaped so it can later be projected to a durable ModSession table.
 */
@Service
public class ModSessionService {

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ModRegistryService registry;
    private final List<ModStateStore> stateStores;

    @Autowired
    public ModSessionService(ModRegistryService registry, List<ModStateStore> stateStores) {
        this.registry = registry;
        this.stateStores = List.copyOf(stateStores);
    }

    /** Lightweight constructor retained for unit tests and embedders without persistence. */
    public ModSessionService() {
        this.registry = null;
        this.stateStores = List.of();
    }

    public ModSessionView open(OpenModSessionCommand command, ActorContext actor) {
        if (registry != null && !registry.isInstalled(command.modId(), actor)) {
            throw new ModInstallationRequiredException(command.modId());
        }
        String id = "mod_" + UUID.randomUUID();
        SessionState state = new SessionState(id, actor.tenantId(), actor.userId(), command.modId());
        String surfaceId = command.surfaceId() == null || command.surfaceId().isBlank()
                ? defaultSurface(command.modId(), actor) : command.surfaceId();
        Map<String, Object> surfaceState = new LinkedHashMap<>();
        if (command.resourceId() != null && !command.resourceId().isBlank()) surfaceState.put("resourceId", command.resourceId());
        if (command.title() != null && !command.title().isBlank()) surfaceState.put("title", command.title());
        if (command.resourceId() != null && !command.resourceId().isBlank()) {
            for (ModStateStore store : stateStores) {
                Map<String, Object> loaded = store.load(command.modId(), command.resourceId(), actor);
                if (loaded != null) loaded.forEach(surfaceState::putIfAbsent);
            }
        }
        state.surfaces.put(surfaceId, new SurfaceState(surfaceId, surfaceRole(command.modId(), surfaceId, actor),
                presentation(command.presentation()), surfaceState));
        state.context.put("current.mod", command.modId());
        state.context.put("current.surface", surfaceId);
        sessions.put(id, state);
        return view(state);
    }

    public ModSessionView get(String id, ActorContext actor) {
        return view(require(id, actor));
    }

    public ModSessionView command(String id, ModCommand command, ActorContext actor) {
        SessionState state = require(id, actor);
        String name = command.command().trim().toLowerCase();
        if (name.equals("close") || name.equals("unmount")) {
            state.status = "SUSPENDED";
        } else if (name.equals("open") || name.equals("resume")) {
            state.status = "ACTIVE";
        }
        SurfaceState surface = state.surfaces.values().stream().findFirst().orElse(null);
        if (surface != null) {
            surface.state.put("lastCommand", name);
            surface.state.putAll(command.arguments());
            persistState(state, surface, actor, name);
        }
        state.context.put("lastCommand", name);
        state.updatedAt = Instant.now();
        return view(state);
    }

    private void persistState(SessionState state, SurfaceState surface, ActorContext actor, String event) {
        String resourceId = value(surface.state.get("resourceId"));
        if (resourceId == null) return;
        Map<String, Object> snapshot = Map.copyOf(surface.state);
        for (ModStateStore store : stateStores) {
            store.save(state.modId, resourceId, event, snapshot, actor);
        }
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private SessionState require(String id, ActorContext actor) {
        SessionState state = sessions.get(id);
        if (state == null || !state.tenantId.equals(actor.tenantId()) || !state.userId.equals(actor.userId())) {
            throw new IllegalArgumentException("Mod session not found: " + id);
        }
        return state;
    }

    private String defaultSurface(String modId, ActorContext actor) {
        if (registry == null) return "main";
        return registry.get(modId, actor).surfaces().stream()
                .map(ModSurfaceDeclaration::id)
                .findFirst()
                .orElse("main");
    }

    private String surfaceRole(String modId, String surfaceId, ActorContext actor) {
        if (registry == null) return "surface";
        return registry.get(modId, actor).surfaces().stream()
                .filter(surface -> surface.id().equals(surfaceId))
                .map(ModSurfaceDeclaration::role)
                .findFirst()
                .orElse("surface");
    }

    private static String presentation(String value) {
        return value == null || value.isBlank() ? "docked" : value;
    }

    private ModSessionView view(SessionState state) {
        List<ModSurfaceView> surfaces = state.surfaces.values().stream()
                .map(surface -> new ModSurfaceView(surface.surfaceId, state.modId, surface.role,
                        surface.presentation, state.status.equals("ACTIVE"), Map.copyOf(surface.state),
                        commands(state.modId, state.tenantId, state.userId), state.updatedAt))
                .toList();
        return new ModSessionView(state.sessionId, state.modId, state.status, surfaces,
                Map.copyOf(state.context), state.updatedAt);
    }

    private List<String> commands(String modId, String tenantId, String userId) {
        if (registry == null) return List.of();
        ActorContext actor = new ActorContext(tenantId, userId, Set.of(), Set.of());
        return registry.get(modId, actor).capabilities().stream()
                .map(ModCapabilityDeclaration::id)
                .toList();
    }

    private static final class SessionState {
        final String sessionId;
        final String tenantId;
        final String userId;
        final String modId;
        final Map<String, SurfaceState> surfaces = new LinkedHashMap<>();
        final Map<String, Object> context = new LinkedHashMap<>();
        volatile String status = "ACTIVE";
        volatile Instant updatedAt = Instant.now();
        SessionState(String sessionId, String tenantId, String userId, String modId) {
            this.sessionId = sessionId; this.tenantId = tenantId; this.userId = userId; this.modId = modId;
        }
    }

    private static final class SurfaceState {
        final String surfaceId;
        final String role;
        final String presentation;
        final Map<String, Object> state;
        SurfaceState(String surfaceId, String role, String presentation, Map<String, Object> state) {
            this.surfaceId = surfaceId; this.role = role; this.presentation = presentation; this.state = state;
        }
    }
}
