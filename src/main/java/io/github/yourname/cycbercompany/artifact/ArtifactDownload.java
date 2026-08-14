package io.github.yourname.cycbercompany.artifact;

import java.nio.file.Path;

public record ArtifactDownload(ArtifactView artifact, Path path) {
}
