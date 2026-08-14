package io.github.yourname.cycbercompany.node;

public record RegisterNodeResult(
        String nodeId,
        String nodeSecret,
        String websocketUrl,
        NodeConnectionView node) {
}
