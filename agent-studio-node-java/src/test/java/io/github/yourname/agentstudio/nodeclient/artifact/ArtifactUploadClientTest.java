package io.github.yourname.agentstudio.nodeclient.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactUploadClientTest {

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void replacesTheLocalPathWithAnHttpArtifactReference() throws Exception {
        byte[] expected = "png bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<String> runId = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/node/artifacts", exchange -> {
            uploaded.set(exchange.getRequestBody().readAllBytes());
            runId.set(exchange.getRequestHeaders().getFirst("X-Agent-Studio-Run-Id"));
            byte[] response = """
                    {"id":"art-1","runId":"run-1","artifactType":"screenshot","filename":"shot.png",\
                    "mimeType":"image/png","sizeBytes":9,"digest":"sha256:test","downloadUrl":"/api/v1/artifacts/art-1"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        Path artifactRoot = temporaryDirectory.resolve("artifacts");
        Path screenshot = artifactRoot.resolve("browser/run-1/screenshots/shot.png");
        Files.createDirectories(screenshot.getParent());
        Files.write(screenshot, expected);
        ArtifactUploadClient client = new ArtifactUploadClient(
                new ObjectMapper(), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "node-1", "secret", artifactRoot);

        ToolExecutionResult result = client.uploadIfPresent("run-1", ToolExecutionResult.success(Map.of(
                "artifactPath", "browser/run-1/screenshots/shot.png",
                "artifactType", "screenshot",
                "mimeType", "image/png")));

        assertTrue(result.success());
        assertFalse(result.result().containsKey("artifactPath"));
        assertNotNull(result.result().get("artifact"));
        assertEquals("run-1", runId.get());
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(uploaded.get(), StandardCharsets.UTF_8));
        assertTrue(Files.notExists(screenshot));
    }
}
