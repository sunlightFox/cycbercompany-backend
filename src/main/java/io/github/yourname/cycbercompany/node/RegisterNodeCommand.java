package io.github.yourname.cycbercompany.node;

public record RegisterNodeCommand(
        String name,
        String hostname,
        String osName,
        String osArch,
        String clientVersion) {
}
