package io.github.yourname.agentstudio.orchestration;

import java.util.List;

/**
 * Server-owned execution category for a persisted run.
 *
 * <p>A selected node enables a general native-tool loop. The model decides whether an advertised
 * tool is needed for the current request; it is never inferred from a keyword catalog.
 */
enum RunExecutionMode {
    CONVERSATIONAL,
    NODE_INTERACTION,
    CODING;

    static RunExecutionMode from(CreateRunCommand command) {
        if (command == null || command.nodeId() == null || command.nodeId().isBlank()) {
            return CONVERSATIONAL;
        }
        return NODE_INTERACTION;
    }

    static RunExecutionMode fromPersisted(
            String nodeId,
            String userText,
            String workingDirectory,
            List<String> requestedTools) {
        return from(new CreateRunCommand(
                "legacy", userText == null ? "" : userText, null, null, List.of(), List.of(), List.of(),
                requestedTools, nodeId, workingDirectory));
    }

    boolean usesNativeToolLoop() {
        return this != CONVERSATIONAL;
    }

    boolean requiresDeliveryGate() {
        return usesNativeToolLoop();
    }

}
