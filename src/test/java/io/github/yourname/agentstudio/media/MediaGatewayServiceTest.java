package io.github.yourname.agentstudio.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MediaGatewayServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void proxiesVideoRangeWithoutExposingTheProviderUrl() throws Exception {
        byte[] video = "demo-video".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video.mp4", exchange -> {
            assertEquals("bytes=0-3", exchange.getRequestHeaders().getFirst("Range"));
            assertEquals("https://source.example/watch", exchange.getRequestHeaders().getFirst("Referer"));
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-3/10");
            exchange.sendResponseHeaders(206, video.length);
            exchange.getResponseBody().write(video);
            exchange.close();
        });
        server.start();

        String upstream = "http://127.0.0.1:" + server.getAddress().getPort() + "/video.mp4";
        MediaGatewayService gateway = new MediaGatewayService(new StubSelection(playback(upstream)));
        MediaPlaybackView opened = gateway.open(new MediaResolveCommand("demo", "source", "1"), null);

        assertTrue(opened.streamUrl().startsWith("/api/v1/media/stream/"));
        assertFalse(opened.streamUrl().contains("127.0.0.1"));
        String token = opened.streamUrl().substring(opened.streamUrl().lastIndexOf('/') + 1);
        MediaGatewayService.GatewayStream response = gateway.openStream(token, "bytes=0-3");

        assertNotNull(response);
        assertEquals(206, response.status());
        assertEquals("bytes 0-3/10", response.responseHeaders().get("Content-Range"));
        try (InputStream input = response.stream()) {
            assertEquals("demo-video", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rewritesHlsChildUrlsBackThroughTheDemoGateway() throws Exception {
        String playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\nsegment.ts\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/index.m3u8", exchange -> {
            byte[] body = playlist.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        String upstream = "http://127.0.0.1:" + server.getAddress().getPort() + "/index.m3u8";
        MediaGatewayService gateway = new MediaGatewayService(new StubSelection(playback(upstream)));
        MediaPlaybackView opened = gateway.open(new MediaResolveCommand("demo", "source", "1"), null);
        String token = opened.streamUrl().substring(opened.streamUrl().lastIndexOf('/') + 1);
        MediaGatewayService.GatewayStream response = gateway.openStream(token, null);

        assertNotNull(response);
        String rewritten = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("/api/v1/media/stream/"));
        assertFalse(rewritten.contains("segment.ts\n"));
        assertFalse(rewritten.contains("key.bin"));
    }

    private static MediaPlaybackView playback(String streamUrl) {
        return new MediaPlaybackView("READY", "demo", "source", "1", streamUrl, null,
                "video/mp4", 0, List.of(), "", Map.of("Referer", "https://source.example/watch"));
    }

    private static final class StubSelection extends MediaPlaybackSelectionService {
        private final MediaPlaybackView playback;

        private StubSelection(MediaPlaybackView playback) {
            super(null);
            this.playback = playback;
        }

        @Override
        public MediaPlaybackView resolve(MediaResolveCommand command, String sourceUrl) {
            return playback;
        }
    }
}
