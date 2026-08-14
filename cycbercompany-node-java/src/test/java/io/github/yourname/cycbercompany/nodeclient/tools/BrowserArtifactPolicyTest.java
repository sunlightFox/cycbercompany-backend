package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BrowserArtifactPolicyTest {

    @Test
    void generatedArtifactsAlwaysStayInsideTheConfiguredRoot() throws Exception {
        Path root = Files.createTempDirectory("cycbercompany-browser-artifact-root").toRealPath();
        try (BrowserTool tool = new BrowserTool(HttpClient.newHttpClient(), root)) {
            Path screenshot = tool.createArtifactPath("../../outside", "screenshots", ".png");
            Path trace = tool.createArtifactPath("run-1", "traces", ".zip");

            assertTrue(screenshot.startsWith(root));
            assertTrue(trace.startsWith(root));
            for (Path segment : root.relativize(screenshot)) {
                assertNotEquals("..", segment.toString());
            }
            for (Path segment : root.relativize(trace)) {
                assertNotEquals("..", segment.toString());
            }
            assertFalse(screenshot.isAbsolute() && !screenshot.normalize().startsWith(root));
        }
    }
}
