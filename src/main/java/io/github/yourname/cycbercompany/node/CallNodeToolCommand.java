package io.github.yourname.cycbercompany.node;

import java.util.Map;

public record CallNodeToolCommand(Map<String, Object> arguments, Integer timeoutSeconds) {
}
