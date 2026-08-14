package io.github.yourname.cycbercompany.node;

import java.util.List;

public record NodeDetailView(NodeConnectionView node, List<NodeToolView> tools) {
}
