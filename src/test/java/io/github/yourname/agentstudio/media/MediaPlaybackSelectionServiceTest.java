package io.github.yourname.agentstudio.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MediaPlaybackSelectionServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void promotesOnlyAResolvedAndVerifiedMediaStream() throws Exception {
        byte[] body = new byte[] {0, 1, 2, 3};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/video.mp4";
        StubSources sources = new StubSources(streamUrl);
        MediaPlaybackSelectionService selection = new MediaPlaybackSelectionService(sources);

        MediaSearchView result = selection.search("demo", null, null);

        assertEquals("good", result.items().getFirst().id());
        assertTrue(result.items().getFirst().playable());
        assertEquals("READY", result.items().getFirst().availability());
        assertFalse(result.items().get(1).playable());
        assertEquals("UNVERIFIED", result.items().get(1).availability());

        MediaPlaybackView cached = selection.resolve(new MediaResolveCommand("good", "direct", "1"), null);
        assertEquals(streamUrl, cached.streamUrl());
        assertEquals(0, sources.normalResolveCalls.get());
    }

    @Test
    void probesAPlayableSourceBeyondTheFirstSixConfiguredSources() throws Exception {
        byte[] body = new byte[] {0, 1, 2, 3};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        String streamUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/video.mp4";
        MediaPlaybackSelectionService selection = new MediaPlaybackSelectionService(new ManySources(streamUrl));

        MediaSearchView result = selection.search("demo", null, null);

        assertEquals("playable", result.items().getFirst().id());
        assertTrue(result.items().getFirst().playable());
    }

    private static final class StubSources extends TvBoxConfigService {
        private final String streamUrl;
        private final AtomicInteger normalResolveCalls = new AtomicInteger();

        private StubSources(String streamUrl) {
            super(new ObjectMapper(), true);
            this.streamUrl = streamUrl;
        }

        @Override
        public MediaSearchView search(String query, String sourceUrl, String sourceId) {
            return new MediaSearchView(query, "READY", "", List.of(
                    new MediaItemView("page", "Demo", "video", "page", "Page", null,
                            false, "WEBSITE_PAGE", "ANONYMOUS", null),
                    new MediaItemView("good", "Demo", "video", "direct", "Direct", null,
                            false, "UNVERIFIED", "ANONYMOUS", null)), List.of("page", "direct"));
        }

        @Override
        MediaPlaybackView probePlayback(MediaResolveCommand command, String sourceUrl, Duration timeout) {
            if ("good".equals(command.mediaId())) {
                return new MediaPlaybackView("READY", command.mediaId(), command.sourceId(), command.episodeId(),
                        streamUrl, null, "video/mp4", 0, List.of(), "");
            }
            return new MediaPlaybackView("WEBSITE_PAGE", command.mediaId(), command.sourceId(), command.episodeId(),
                    null, "https://example.test/watch", null, 0, List.of(), "");
        }

        @Override
        public MediaPlaybackView resolvePlayback(MediaResolveCommand command, String sourceUrl) {
            normalResolveCalls.incrementAndGet();
            return new MediaPlaybackView("UNAVAILABLE", command.mediaId(), command.sourceId(), command.episodeId(),
                    null, null, 0, List.of(), "not cached");
        }
    }

    private static final class ManySources extends TvBoxConfigService {
        private final String streamUrl;

        private ManySources(String streamUrl) {
            super(new ObjectMapper(), true);
            this.streamUrl = streamUrl;
        }

        @Override
        public MediaSearchView search(String query, String sourceUrl, String sourceId) {
            java.util.ArrayList<MediaItemView> items = new java.util.ArrayList<>();
            for (int index = 1; index <= 7; index++) {
                String source = "source-" + index;
                items.add(new MediaItemView(index == 7 ? "playable" : "candidate-" + index,
                        "Demo", "video", source, source, null, false, "UNVERIFIED", "ANONYMOUS", null));
            }
            return new MediaSearchView(query, "READY", "", List.copyOf(items), List.of());
        }

        @Override
        MediaPlaybackView probePlayback(MediaResolveCommand command, String sourceUrl, Duration timeout) {
            if ("playable".equals(command.mediaId())) {
                return new MediaPlaybackView("READY", command.mediaId(), command.sourceId(), command.episodeId(),
                        streamUrl, null, "video/mp4", 0, List.of(), "");
            }
            return new MediaPlaybackView("UNAVAILABLE", command.mediaId(), command.sourceId(), command.episodeId(),
                    null, null, 0, List.of(), "not playable");
        }
    }
}
