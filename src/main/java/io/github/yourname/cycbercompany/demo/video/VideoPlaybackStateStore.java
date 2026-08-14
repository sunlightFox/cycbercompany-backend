package io.github.yourname.cycbercompany.demo.video;

import io.github.yourname.cycbercompany.media.MediaProgressCommand;
import io.github.yourname.cycbercompany.media.MediaProgressService;
import io.github.yourname.cycbercompany.media.MediaProgressView;
import io.github.yourname.cycbercompany.mod.ModStateStore;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Video Demo-owned memory adapter. The platform dispatches a generic surface
 * event; only this class understands episodes, sources, and watch position.
 */
@Component
public final class VideoPlaybackStateStore implements ModStateStore {
    private static final String MOD_ID = "video-player";
    private static final List<String> PERSISTED_EVENTS = List.of(
            "play", "pause", "seek", "next-episode", "previous-episode", "close", "resume");

    private final MediaProgressService progress;

    public VideoPlaybackStateStore(MediaProgressService progress) {
        this.progress = progress;
    }

    @Override
    public Map<String, Object> load(String modId, String resourceId, ActorContext actor) {
        if (!MOD_ID.equals(modId) || resourceId == null || resourceId.isBlank()) {
            return Map.of();
        }
        MediaProgressView value = progress.get(modId, resourceId, actor);
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("positionMs", value.positionMs());
        state.put("durationMs", value.durationMs());
        if (value.sourceId() != null) state.put("sourceId", value.sourceId());
        if (value.episodeId() != null) state.put("episodeId", value.episodeId());
        state.put("completed", value.completed());
        return Map.copyOf(state);
    }

    @Override
    public void save(String modId, String resourceId, String event, Map<String, Object> state, ActorContext actor) {
        if (!MOD_ID.equals(modId) || resourceId == null || resourceId.isBlank() || !PERSISTED_EVENTS.contains(event)) {
            return;
        }
        progress.save(new MediaProgressCommand(
                modId,
                resourceId,
                text(state.get("sourceId")),
                text(state.get("episodeId")),
                number(state.get("positionMs")),
                number(state.get("durationMs")),
                bool(state.get("completed"))), actor);
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }
}
