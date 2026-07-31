package io.github.yourname.agentstudio.node;

public record RegisterNodeResult(
        String nodeId,
        String nodeSecret,
        String websocketUrl,
        NodeConnectionView node) {
}
