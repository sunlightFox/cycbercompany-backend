package io.github.yourname.agentstudio.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TvBoxConfigServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void importsEnvelopeWithoutExecutingRemoteSpiders() throws Exception {
        String json = "{\"spider\":\"http://example.invalid/spider.jar\",\"sites\":["
                + "{\"key\":\"demo\",\"name\":\"Demo\",\"api\":\"csp_demo\",\"searchable\":1,\"quickSearch\":1},"
                + "{\"key\":\"js\",\"name\":\"JS\",\"api\":\"https://example.invalid/a.js\",\"ext\":{\"siteUrl\":\"https://www.libvio.pw/\"}},"
                + "{\"key\":\"cloud\",\"name\":\"盘搜\",\"api\":\"csp_S_zpsGuard\",\"searchable\":1,\"ext\":{\"siteUrl\":\"https://so.example.invalid/\"}},"
                + "{\"key\":\"download\",\"name\":\"新6V┃磁力\",\"api\":\"csp_SixVGuard\",\"searchable\":1,\"ext\":\"https://www.xb6v.com/\"}],"
                + "\"lives\":[{\"name\":\"News\"}]}";
        byte[] body = ("BM\u0000\u0000**" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.ISO_8859_1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tv", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/tv");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        TvBoxConfigService service = new TvBoxConfigService(new ObjectMapper(), true);
        MediaCatalogView catalog = service.catalog("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");

        assertEquals("CONFIG_IMPORTED", catalog.runtimeStatus());
        assertEquals(4, catalog.sources().size());
        assertTrue(catalog.sources().stream().allMatch(MediaSourceView::requiresIsolatedRuntime));
        assertEquals("ISOLATED_RUNTIME_REQUIRED", catalog.sources().getFirst().status());
        assertEquals("https://www.libvio.pw/", catalog.sources().get(1).websiteUrl());
        assertEquals("WEBSITE_DISCOVERED", catalog.sources().get(1).status());
        assertEquals("ANONYMOUS", catalog.sources().get(1).accessMode());
        assertEquals("CLOUD_INDEX", catalog.sources().get(2).status());
        assertTrue(!catalog.sources().get(2).searchable());
        assertEquals("DOWNLOAD_ONLY", catalog.sources().get(3).status());
        assertTrue(!catalog.sources().get(3).searchable());
        assertEquals(List.of("News"), catalog.liveGroups());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("Spider")));
        assertEquals("RUNTIME_REQUIRED", service.search("demo", catalog.sourceUrl()).status());
    }
}
