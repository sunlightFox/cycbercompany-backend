package io.github.yourname.agentstudio.node;

import java.util.Map;

public record CallNodeToolCommand(Map<String, Object> arguments, Integer timeoutSeconds) {
}
