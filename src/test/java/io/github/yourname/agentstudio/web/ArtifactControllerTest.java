package io.github.yourname.agentstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.artifact.ArtifactDownload;
import io.github.yourname.agentstudio.artifact.ArtifactService;
import io.github.yourname.agentstudio.artifact.ArtifactView;
import io.github.yourname.agentstudio.node.NodeConnectionEntity;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.security.CurrentActorProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class ArtifactControllerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void authenticatesTheNodeBeforeStoringUploadedBytesInItsTenant() throws Exception {
        NodeService nodes = mock(NodeService.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        ArtifactController controller = new ArtifactController(nodes, artifacts, actors);
        NodeConnectionEntity node = new NodeConnectionEntity(
                "node-1", "tenant-1", "Teaching PC", "host", "Windows", "amd64", "1", "hash", Instant.now());
        byte[] bytes = "trace-content".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + "a".repeat(64);
        ArtifactView stored = new ArtifactView(
                "art-1", "run-1", "playwright-trace", "trace.zip", "application/zip",
                bytes.length, digest, "/api/v1/artifacts/art-1", Instant.now());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(bytes);
        request.setContentType("application/zip");
        when(nodes.authenticateNode("node-1", "node-secret")).thenReturn(node);
        when(artifacts.store(
                eq("tenant-1"), eq("node-1"), eq("run-1"), eq("playwright-trace"),
                eq("trace.zip"), eq("application/zip"), eq(digest), eq((long) bytes.length), any(InputStream.class)))
                .thenReturn(stored);

        Object response = controller.upload(
                "node-1", "Bearer node-secret", "run-1", "playwright-trace", "trace.zip", digest, request);

        assertThat(response).isEqualTo(stored);
        verify(nodes).authenticateNode("node-1", "node-secret");
        verify(artifacts).store(
                eq("tenant-1"), eq("node-1"), eq("run-1"), eq("playwright-trace"),
                eq("trace.zip"), eq("application/zip"), eq(digest), eq((long) bytes.length), any(InputStream.class));
    }

    @Test
    void delegatesDownloadAuthorizationToTheCurrentTenantAndReturnsOnlyManagedMetadata() throws Exception {
        NodeService nodes = mock(NodeService.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        ArtifactController controller = new ArtifactController(nodes, artifacts, actors);
        ActorContext actor = ActorContext.local();
        Path storedFile = temporaryDirectory.resolve("private-store/trace.zip");
        Files.createDirectories(storedFile.getParent());
        Files.writeString(storedFile, "trace", StandardCharsets.UTF_8);
        ArtifactView view = new ArtifactView(
                "art-1", "run-1", "playwright-trace", "trace.zip", "application/zip",
                Files.size(storedFile), "sha256:" + "b".repeat(64), "/api/v1/artifacts/art-1", Instant.now());
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(actors.current(request)).thenReturn(actor);
        when(artifacts.download("art-1", actor)).thenReturn(new ArtifactDownload(view, storedFile));

        var response = controller.download("art-1", request);

        verify(actors).current(request);
        verify(artifacts).download("art-1", actor);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("trace.zip");
        Resource body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getInputStream().readAllBytes()).isEqualTo("trace".getBytes(StandardCharsets.UTF_8));
        assertThat(view.toString()).doesNotContain(temporaryDirectory.toString(), "private-store");
    }
}
