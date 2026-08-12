package io.github.yourname.agentstudio.media;

import io.github.yourname.agentstudio.mod.ModCommand;
import io.github.yourname.agentstudio.mod.ModRegistryService;
import io.github.yourname.agentstudio.mod.ModSessionService;
import io.github.yourname.agentstudio.demo.video.VideoDemoService;
import io.github.yourname.agentstudio.tool.CodingWorkspaceScope;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.tool.ToolDescriptor;
import io.github.yourname.agentstudio.tool.ToolDiscoveryRequest;
import io.github.yourname.agentstudio.tool.ToolInvocationRequest;
import io.github.yourname.agentstudio.tool.ToolProvider;
import io.github.yourname.agentstudio.tool.ToolProviderResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Exposes Video Mod capabilities to the normal Agent ToolRouter. */
@Service
public class MediaToolProvider implements ToolProvider {

    public static final String PROVIDER_ID = "media";
    private final VideoDemoService demo;
    private final TvBoxConfigService legacySources;
    private final MediaProgressService legacyProgress;
    private final ModSessionService sessions;
    private final ModRegistryService registry;

    @Autowired
    public MediaToolProvider(VideoDemoService demo, ModSessionService sessions,
                             ModRegistryService registry) {
        this.demo = demo;
        this.legacySources = null;
        this.legacyProgress = null;
        this.sessions = sessions;
        this.registry = registry;
    }

    /** Compatibility constructor for provider discovery tests without persistence. */
    public MediaToolProvider(TvBoxConfigService sources, MediaProgressService progress, ModSessionService sessions) {
        this.demo = null;
        this.legacySources = sources;
        this.legacyProgress = progress;
        this.sessions = sessions;
        this.registry = null;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ToolDescriptor> discover(ToolDiscoveryRequest request) {
        if (registry != null && !registry.isInstalled("video-player", request.actor())) {
            return List.of();
        }
        return List.of(
                descriptor("media.search", "Search the installed Video Mod media sources. Remote provider code is isolated.",
                        objectSchema(Map.of("query", property("string", "Title or topic to search for")), "query"), RiskLevel.LOW),
                descriptor("media.resolvePlayback", "Resolve one selected media item to a playable stream through the isolated provider runtime.",
                        objectSchema(Map.of("mediaId", property("string", "Media id"),
                                "sourceId", property("string", "Source id"),
                                "episodeId", property("string", "Episode id")), "mediaId"), RiskLevel.MEDIUM),
                descriptor("media.progress.get", "Read structured playback progress for a media item.",
                        objectSchema(Map.of("modId", property("string", "Mod id"), "mediaId", property("string", "Media id")), "modId", "mediaId"), RiskLevel.LOW),
                descriptor("media.progress.save", "Persist structured playback progress for a media item.",
                        objectSchema(Map.of("modId", property("string", "Mod id"), "mediaId", property("string", "Media id"),
                                "positionMs", property("integer", "Current position in milliseconds"),
                                "durationMs", property("integer", "Duration in milliseconds")), "modId", "mediaId"), RiskLevel.MEDIUM),
                descriptor("player.command", "Send a direct command to an active Mod surface: play, pause, seek, next-episode or previous-episode.",
                        objectSchema(Map.of("sessionId", property("string", "Mod session id"), "command", property("string", "Command name"),
                                "arguments", Map.of("type", "object")), "sessionId", "command"), RiskLevel.MEDIUM));
    }

    @Override
    public ToolProviderResult invoke(ToolInvocationRequest request) {
        if (!PROVIDER_ID.equals(request.binding().providerId())) {
            throw new IllegalArgumentException("MediaToolProvider cannot invoke binding: " + request.binding().bindingId());
        }
        try {
            return switch (request.binding().providerToolName()) {
                case "media.search" -> search(request);
                case "media.resolvePlayback" -> resolvePlayback(request);
                case "media.progress.get" -> getProgress(request);
                case "media.progress.save" -> saveProgress(request);
                case "player.command" -> playerCommand(request);
                default -> throw new IllegalArgumentException("Unknown media tool: " + request.binding().providerToolName());
            };
        } catch (Exception ex) {
            return new ToolProviderResult("FAILED", false, Map.of(), message(ex), null);
        }
    }

    private ToolProviderResult search(ToolInvocationRequest request) {
        String query = requiredString(request.arguments(), "query");
        MediaSearchView result = demo != null ? demo.search(query, null, null) : legacySources.search(query, null);
        return success(Map.of("query", result.query(), "status", result.status(), "message", result.message(),
                "items", result.items(), "sourceKeys", result.sourceKeys()));
    }

    private ToolProviderResult getProgress(ToolInvocationRequest request) {
        String modId = requiredString(request.arguments(), "modId");
        String mediaId = requiredString(request.arguments(), "mediaId");
        MediaProgressView value = demo != null ? demo.progress(modId, mediaId, request.actor())
                : legacyProgress == null ? null : legacyProgress.get(modId, mediaId, request.actor());
        return value == null ? success(Map.of("found", false, "modId", modId, "mediaId", mediaId))
                : success(Map.of("found", true, "progress", value));
    }

    private ToolProviderResult resolvePlayback(ToolInvocationRequest request) {
        MediaResolveCommand command = new MediaResolveCommand(
                requiredString(request.arguments(), "mediaId"),
                stringValue(request.arguments().get("sourceId")),
                stringValue(request.arguments().get("episodeId")));
        MediaPlaybackView result = demo != null ? demo.resolve(command, null) : legacySources.resolvePlayback(command, null);
        return success(Map.of("playback", result));
    }

    private ToolProviderResult saveProgress(ToolInvocationRequest request) {
        String modId = requiredString(request.arguments(), "modId");
        String mediaId = requiredString(request.arguments(), "mediaId");
        MediaProgressCommand command = new MediaProgressCommand(modId, mediaId,
                stringValue(request.arguments().get("sourceId")), stringValue(request.arguments().get("episodeId")),
                longValue(request.arguments().get("positionMs")), longValue(request.arguments().get("durationMs")),
                booleanValue(request.arguments().get("completed")));
        MediaProgressView saved = demo != null ? demo.saveProgress(command, request.actor())
                : legacyProgress.save(command, request.actor());
        return success(Map.of("progress", saved));
    }

    private ToolProviderResult playerCommand(ToolInvocationRequest request) {
        String sessionId = requiredString(request.arguments(), "sessionId");
        String command = requiredString(request.arguments(), "command");
        Object rawArguments = request.arguments().get("arguments");
        Map<String, Object> arguments = rawArguments instanceof Map<?, ?> map ? copyMap(map) : Map.of();
        return success(Map.of("session", sessions.command(sessionId, new ModCommand(command, arguments), request.actor())));
    }

    private static ToolDescriptor descriptor(String name, String description, Map<String, Object> schema, RiskLevel risk) {
        return new ToolDescriptor("media:" + name, name, PROVIDER_ID, name, description, risk, false, schema, Map.of());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) schema.put("required", List.of(required));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (key != null && value != null) copy.put(key.toString(), value); });
        return copy;
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Argument '" + name + "' must be a non-empty string.");
        return text.trim();
    }

    private static String stringValue(Object value) { return value == null ? null : value.toString(); }
    private static Long longValue(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static Boolean booleanValue(Object value) { return value instanceof Boolean b ? b : null; }
    private static ToolProviderResult success(Map<String, Object> value) { return new ToolProviderResult("SUCCEEDED", true, value, "", null); }
    private static String message(Exception ex) { return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage(); }
}
