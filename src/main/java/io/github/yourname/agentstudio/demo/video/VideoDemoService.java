package io.github.yourname.agentstudio.demo.video;

import io.github.yourname.agentstudio.media.MediaCatalogView;
import io.github.yourname.agentstudio.media.MediaGatewayService;
import io.github.yourname.agentstudio.media.MediaPlaybackSelectionService;
import io.github.yourname.agentstudio.media.MediaPlaybackView;
import io.github.yourname.agentstudio.media.MediaProgressCommand;
import io.github.yourname.agentstudio.media.MediaProgressService;
import io.github.yourname.agentstudio.media.MediaProgressView;
import io.github.yourname.agentstudio.media.MediaResolveCommand;
import io.github.yourname.agentstudio.media.MediaRuntimeClient;
import io.github.yourname.agentstudio.media.MediaRuntimeStatusView;
import io.github.yourname.agentstudio.media.MediaSearchView;
import io.github.yourname.agentstudio.media.TvBoxConfigService;
import io.github.yourname.agentstudio.security.ActorContext;
import org.springframework.stereotype.Service;

/**
 * Private application service for the Video Demo. The platform sees only the
 * Mod manifest/session contracts; all source, runtime, probing, gateway and
 * playback-memory decisions stay behind this boundary.
 */
@Service
public final class VideoDemoService {
    private final TvBoxConfigService config;
    private final MediaPlaybackSelectionService selection;
    private final MediaGatewayService gateway;
    private final MediaRuntimeClient runtime;
    private final MediaProgressService progress;

    public VideoDemoService(TvBoxConfigService config,
                            MediaPlaybackSelectionService selection,
                            MediaGatewayService gateway,
                            MediaRuntimeClient runtime,
                            MediaProgressService progress) {
        this.config = config;
        this.selection = selection;
        this.gateway = gateway;
        this.runtime = runtime;
        this.progress = progress;
    }

    public MediaCatalogView catalog(String sourceUrl) {
        return config.catalog(sourceUrl);
    }

    public MediaSearchView search(String query, String sourceUrl, String sourceId) {
        return selection.search(query, sourceUrl, sourceId);
    }

    public MediaPlaybackView resolve(MediaResolveCommand command, String sourceUrl) {
        return gateway.open(command, sourceUrl);
    }

    public MediaGatewayService.GatewayStream stream(String token, String range) {
        return gateway.openStream(token, range);
    }

    public MediaRuntimeStatusView runtime() {
        return runtime.status();
    }

    public MediaProgressView progress(String modId, String resourceId, ActorContext actor) {
        return progress.get(modId, resourceId, actor);
    }

    public MediaProgressView saveProgress(MediaProgressCommand command, ActorContext actor) {
        return progress.save(command, actor);
    }
}
