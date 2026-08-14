package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserToolFailureDiagnosticsTest {

    @TempDir
    Path artifactRoot;

    @Test
    void failedPrimaryOperationReportsStructuredDiagnosticsWithoutStartingABrowser() throws Exception {
        BrowserTool tool = new BrowserTool(HttpClient.newHttpClient(), artifactRoot);

        // 没有 open 时 click 必然失败。此时不应为了取证启动 Playwright，
        // 但上层仍可根据结构化字段判断没有可保留的页面现场。
        var result = tool.click("run-diagnostic", Map.of("selector", "#missing"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("No active browser page");
        assertThat(result.result())
                .containsEntry("operation", "browser.click")
                .containsEntry("diagnosticArtifactCaptured", false)
                .doesNotContainKey("artifactPath");
        assertThat(Files.list(artifactRoot).findAny()).isEmpty();
    }
}
