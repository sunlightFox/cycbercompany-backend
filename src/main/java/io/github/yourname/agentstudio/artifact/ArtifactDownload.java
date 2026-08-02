package io.github.yourname.agentstudio.artifact;

import java.nio.file.Path;

public record ArtifactDownload(ArtifactView artifact, Path path) {
}
