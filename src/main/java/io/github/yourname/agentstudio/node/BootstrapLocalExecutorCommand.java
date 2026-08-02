package io.github.yourname.agentstudio.node;

/** Metadata supplied by the local companion when it provisions its managed connection. */
public record BootstrapLocalExecutorCommand(
        String name,
        String hostname,
        String osName,
        String osArch,
        String clientVersion) {
}
