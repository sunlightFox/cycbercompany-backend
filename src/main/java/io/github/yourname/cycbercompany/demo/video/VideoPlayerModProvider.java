package io.github.yourname.cycbercompany.demo.video;

import io.github.yourname.cycbercompany.mod.ModCapabilityDeclaration;
import io.github.yourname.cycbercompany.mod.ModManifestView;
import io.github.yourname.cycbercompany.mod.ModProvider;
import io.github.yourname.cycbercompany.mod.ModSurfaceDeclaration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Video Demo registration. The platform only sees this normalized manifest. */
@Component
public final class VideoPlayerModProvider implements ModProvider {
    @Override
    public ModManifestView manifest() {
        List<ModSurfaceDeclaration> surfaces = List.of(
                new ModSurfaceDeclaration("player", "media-player", List.of("docked", "floating", "fullscreen")));
        List<ModCapabilityDeclaration> capabilities = List.of(
                capability("media.search", "Search configured media sources", "provider-runtime", true,
                        Map.of("query", "string")),
                capability("media.resolvePlayback", "Resolve a playable stream", "provider-runtime", true,
                        Map.of("mediaId", "string", "sourceId", "string", "episodeId", "string")),
                capability("player.play", "Start playback", "direct", false, Map.of("positionMs", "integer")),
                capability("player.pause", "Pause and persist progress", "direct", false, Map.of("positionMs", "integer")),
                capability("player.seek", "Seek within the current item", "direct", false, Map.of("positionMs", "integer")),
                capability("episode.next", "Switch to the next episode", "direct", false, Map.of()),
                capability("episode.previous", "Switch to the previous episode", "direct", false, Map.of()),
                capability("media.explain", "Answer questions using current media context", "agent", true,
                        Map.of("question", "string")));
        return new ModManifestView(
                "video-player",
                "Video Mod",
                "0.1.0",
                "Search, play, resume and discuss videos inside the Agent workspace.",
                "media",
                false,
                surfaces,
                capabilities,
                List.of("media_progress", "watch_history", "source_preference"),
                List.of("network.media-source", "media.playback", "subtitle.read"),
                List.of("tvbox", "rss", "custom-api"));
    }

    private static ModCapabilityDeclaration capability(String id, String description, String execution,
                                                        boolean planning, Map<String, Object> schema) {
        return new ModCapabilityDeclaration(id, description, execution, planning,
                new LinkedHashMap<>(schema));
    }
}
