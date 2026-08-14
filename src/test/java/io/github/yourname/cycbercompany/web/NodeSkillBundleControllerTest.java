package io.github.yourname.cycbercompany.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.yourname.cycbercompany.node.NodeService;
import io.github.yourname.cycbercompany.skill.SkillBundleDownload;
import io.github.yourname.cycbercompany.skill.SkillCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;

class NodeSkillBundleControllerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void authenticatesTheNodeAndReturnsOnlyDigestMetadataAndBundleBytes() throws Exception {
        NodeService nodes = mock(NodeService.class);
        SkillCatalog skills = mock(SkillCatalog.class);
        NodeSkillBundleController controller = new NodeSkillBundleController(nodes, skills);
        String releaseHex = "a".repeat(64);
        String releaseDigest = "sha256:" + releaseHex;
        String bundleDigest = "sha256:" + "b".repeat(64);
        Path bundlePath = temporaryDirectory.resolve("bundle.zip").toAbsolutePath();
        byte[] bytes = "deterministic-bundle".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(bundlePath, bytes);
        when(skills.prepareBundle("teaching-skill", releaseDigest))
                .thenReturn(new SkillBundleDownload("teaching-skill", releaseDigest, bundleDigest, bytes.length, bundlePath));

        var response = controller.download("teaching-skill", releaseHex, "node-1", "Bearer node-secret");

        verify(nodes).authenticateNode("node-1", "node-secret");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(NodeSkillBundleController.RELEASE_DIGEST_HEADER)).isEqualTo(releaseDigest);
        assertThat(response.getHeaders().getFirst(NodeSkillBundleController.BUNDLE_DIGEST_HEADER)).isEqualTo(bundleDigest);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("teaching-skill-" + releaseHex);
        Resource body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getInputStream().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void rejectsMalformedDigestBeforeAuthenticatingOrTouchingStorage() {
        NodeService nodes = mock(NodeService.class);
        SkillCatalog skills = mock(SkillCatalog.class);
        NodeSkillBundleController controller = new NodeSkillBundleController(nodes, skills);

        assertThatThrownBy(() -> controller.download("skill", "../release", "node-1", "Bearer secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 hexadecimal");
        verifyNoInteractions(nodes, skills);
    }
}
