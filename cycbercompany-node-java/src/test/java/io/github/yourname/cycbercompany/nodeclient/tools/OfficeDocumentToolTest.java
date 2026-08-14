package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficeDocumentToolTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsReadsAndStagesAnXlsxForArtifactUpload() throws Exception {
        Path artifactRoot = temporaryDirectory.resolve("artifacts");
        Path destination = temporaryDirectory.resolve("report.xlsx");
        OfficeDocumentTool tool = new OfficeDocumentTool(artifactRoot);

        var created = tool.create("run-1", Map.of("path", destination.toString(), "title", "Sales", "content", "Region\tRevenue\nEast\t120"));

        assertThat(created.success()).isTrue();
        assertThat(destination).exists();
        assertThat(created.result()).containsEntry("artifactType", "office-document").containsKey("artifactPath");
        var read = tool.read(Map.of("path", destination.toString()));
        assertThat(read.success()).isTrue();
        assertThat(read.result().get("content").toString()).contains("Sales", "Region", "East");
        assertThat(artifactRoot.resolve(created.result().get("artifactPath").toString())).exists();
    }
}
