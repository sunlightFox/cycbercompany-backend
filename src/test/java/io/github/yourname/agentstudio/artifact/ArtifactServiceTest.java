package io.github.yourname.agentstudio.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.security.ActorContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesDigestPersistsMetadataAndHidesTheStoragePathFromTheView() throws Exception {
        ArtifactRepository repository = mock(ArtifactRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArtifactService service = service(repository);
        byte[] bytes = "trace bytes".getBytes(StandardCharsets.UTF_8);
        String digest = digest(bytes);

        ArtifactView view = service.store(
                "tenant-1", "node-1", "run-1", "playwright-trace", "trace.zip", "application/zip",
                digest, bytes.length, new ByteArrayInputStream(bytes));

        assertThat(view.id()).startsWith("art_");
        assertThat(view.digest()).isEqualTo(digest);
        assertThat(view.downloadUrl()).isEqualTo("/api/v1/artifacts/" + view.id());
        assertThat(view.toString()).doesNotContain(temporaryDirectory.toString(), "storagePath");
        verify(repository).save(any());
    }

    @Test
    void rejectsCorruptUploadBeforePersistingMetadata() throws Exception {
        ArtifactRepository repository = mock(ArtifactRepository.class);
        ArtifactService service = service(repository);
        byte[] bytes = "corrupt".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.store(
                "tenant-1", "node-1", "run-1", "trace", "trace.zip", "application/zip",
                "sha256:" + "0".repeat(64), bytes.length, new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256 verification");
        verify(repository, never()).save(any());
    }

    @Test
    void downloadsOnlyThroughTheCurrentTenantLookup() throws Exception {
        ArtifactRepository repository = mock(ArtifactRepository.class);
        Path stored = temporaryDirectory.resolve("artifacts/art-1/trace.zip");
        Files.createDirectories(stored.getParent());
        Files.writeString(stored, "trace");
        ArtifactEntity entity = new ArtifactEntity(
                "art-1", "local", "node-1", "run-1", "trace", "trace.zip", "application/zip",
                5, digest("trace".getBytes(StandardCharsets.UTF_8)), "art-1/trace.zip", Instant.now());
        when(repository.findByIdAndTenantId("art-1", "local")).thenReturn(Optional.of(entity));
        ArtifactService service = service(repository);

        ArtifactDownload download = service.download("art-1", ActorContext.local());

        assertThat(download.path()).isEqualTo(stored);
        verify(repository).findByIdAndTenantId("art-1", "local");
    }

    private ArtifactService service(ArtifactRepository repository) {
        AppProperties properties = new AppProperties(temporaryDirectory, null, null, null, null, null, null);
        return new ArtifactService(repository, properties);
    }

    private static String digest(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
