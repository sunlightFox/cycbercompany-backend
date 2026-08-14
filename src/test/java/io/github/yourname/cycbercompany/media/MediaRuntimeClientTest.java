package io.github.yourname.cycbercompany.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MediaRuntimeClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void forwardsNormalizedSearchToIsolatedRuntime() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MediaSearchView expected = new MediaSearchView(
                "机器猫", "READY", "", List.of(new MediaItemView(
                        "demo-1", "哆啦A梦", "series", "demo", "Demo", null, true, "READY")), List.of("demo"));
        byte[] body = mapper.writeValueAsBytes(expected);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/media/search", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        MediaCatalogView catalog = new MediaCatalogView(
                "http://example.invalid/tv", "sha256:test", java.time.Instant.now(), "CONFIG_IMPORTED",
                List.of(new MediaSourceView("demo", "Demo", "tvbox-spider", true, true, true, "ISOLATED_RUNTIME_REQUIRED")),
                List.of(), List.of());
        MediaRuntimeClient client = new MediaRuntimeClient(
                mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/media/search");

        MediaSearchView actual = client.search("机器猫", catalog);

        assertEquals("READY", actual.status());
        assertEquals("哆啦A梦", actual.items().getFirst().title());
    }

    @Test
    void resolvesPlaybackAndRejectsNonHttpStreamUrls() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MediaPlaybackView expected = new MediaPlaybackView(
                "READY", "demo-1", "demo", "episode-1", "https://media.example.test/demo.m3u8",
                "application/vnd.apple.mpegurl", 120_000, List.of("https://media.example.test/subtitle.vtt", "file:///secret.vtt"), "");
        byte[] body = mapper.writeValueAsBytes(expected);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/media/search", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        MediaRuntimeClient client = new MediaRuntimeClient(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/media/search");
        MediaPlaybackView actual = client.resolvePlayback(new MediaResolveCommand("demo-1", "demo", "episode-1"), catalog());

        assertEquals("READY", actual.status());
        assertEquals("https://media.example.test/demo.m3u8", actual.streamUrl());
        assertEquals(List.of("https://media.example.test/subtitle.vtt"), actual.subtitleUrls());

        byte[] unsafe = mapper.writeValueAsBytes(new MediaPlaybackView("READY", "demo-1", "demo", null,
                "file:///private/movie.mp4", null, 0, List.of(), ""));
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/media/search", exchange -> {
            exchange.sendResponseHeaders(200, unsafe.length);
            exchange.getResponseBody().write(unsafe);
            exchange.close();
        });
        server.start();
        MediaRuntimeClient unsafeClient = new MediaRuntimeClient(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/media/search");
        MediaPlaybackView rejected = unsafeClient.resolvePlayback(new MediaResolveCommand("demo-1", "demo", null), catalog());

        assertEquals("RUNTIME_UNAVAILABLE", rejected.status());
        assertTrue(rejected.message().contains("unsupported scheme"));
    }

    @Test
    void sendsConfiguredTokenToIsolatedRuntime() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MediaSearchView expected = new MediaSearchView("demo", "READY", "", List.of(), List.of());
        byte[] body = mapper.writeValueAsBytes(expected);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/media/search", exchange -> {
            assertEquals("runtime-secret", exchange.getRequestHeaders().getFirst("X-Media-Runtime-Token"));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        MediaRuntimeClient client = new MediaRuntimeClient(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/media/search", "runtime-secret");

        assertEquals("READY", client.search("demo", catalog()).status());
    }

    @Test
    void reportsWorkerOnlineButAdapterMissing() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        byte[] body = mapper.writeValueAsBytes(Map.of("status", "UP", "adapterConfigured", false));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        MediaRuntimeClient client = new MediaRuntimeClient(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/media/search");

        assertEquals("ADAPTER_REQUIRED", client.status().status());
    }

    @Test
    void requiresAdapterReadinessInsteadOfOnlyAnAdapterCommand() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        byte[] body = mapper.writeValueAsBytes(Map.of(
                "status", "UP", "adapterConfigured", true, "adapterReady", false,
                "adapterMessage", "Video Demo TVBox engine endpoint is not configured."));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        MediaRuntimeClient client = new MediaRuntimeClient(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/media/search");

        MediaRuntimeStatusView status = client.status();
        assertEquals("ADAPTER_REQUIRED", status.status());
        assertTrue(status.message().contains("engine endpoint"));
    }

    private static MediaCatalogView catalog() {
        return new MediaCatalogView("http://example.invalid/tv", "sha256:test", java.time.Instant.now(), "CONFIG_IMPORTED",
                List.of(new MediaSourceView("demo", "Demo", "tvbox-spider", true, true, true, "ISOLATED_RUNTIME_REQUIRED")),
                List.of(), List.of());
    }
}
