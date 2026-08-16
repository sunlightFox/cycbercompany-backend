package io.github.yourname.cycbercompany.orchestration;

import java.util.List;

/**
 * 服务端为持久化 Run 判定的执行类别。
 *
 * <p>只要选择了节点，就启用通用本机工具循环。模型是否真的调用某个已暴露工具，
 * 由工具 schema 和上下文决定；服务端不会用关键词猜测要执行哪类任务。
 */
enum RunExecutionMode {
    /**
     * Backend-, Skill- and MCP-backed interaction without a selected execution node.
     * Node tools are deliberately absent from this mode.
     */
    PLATFORM_INTERACTION,
    /** Retained so already-persisted pre-platform runs remain readable. */
    CONVERSATIONAL,
    /** 与节点交互，但不要求代码交付门禁。 */
    NODE_INTERACTION,
    /** 编码类 Run，需要额外交付验证。 */
    CODING;

    static RunExecutionMode from(CreateRunCommand command) {
        if (command == null || command.nodeId() == null || command.nodeId().isBlank()
                || "auto".equalsIgnoreCase(command.nodeId().trim())) {
            return PLATFORM_INTERACTION;
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
        return this == NODE_INTERACTION || this == CODING;
    }

    boolean usesToolLoop() {
        return this != CONVERSATIONAL;
    }

    boolean requiresDeliveryGate() {
        return this == CODING;
    }

}
