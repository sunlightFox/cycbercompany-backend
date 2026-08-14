package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunExecutionModeTest {

    @Test
    void selectedNodeUsesDynamicInteractionForAGreetingWithoutForcingAToolCall() {
        assertThat(RunExecutionMode.from(command("你好呀", List.of(), "node-1", null)))
                .isEqualTo(RunExecutionMode.NODE_INTERACTION);
    }

    @Test
    void selectedNodeUsesNodeInteractionModeWithoutToolIntentClassification() {
        assertThat(RunExecutionMode.from(command("桌面有什么文件", List.of(), "node-1", null)))
                .isEqualTo(RunExecutionMode.NODE_INTERACTION);
    }

    @Test
    void workspaceTaskAlsoUsesTheDynamicNodeInteractionMode() {
        assertThat(RunExecutionMode.from(command("修复这个接口", List.of(), "node-1", "service")))
                .isEqualTo(RunExecutionMode.NODE_INTERACTION);
    }

    @Test
    void explicitFrontendProjectRequestUsesNodeInteractionWorkflow() {
        assertThat(RunExecutionMode.from(command("在桌面创建一个前端项目，先写一个贪吃蛇小游戏", List.of(), "node-1", null)))
                .isEqualTo(RunExecutionMode.NODE_INTERACTION);
    }

    @Test
    void desktopOrganizationRequestRemainsANodeInteraction() {
        assertThat(RunExecutionMode.from(command("整理桌面的下载文件", List.of(), "node-1", null)))
                .isEqualTo(RunExecutionMode.NODE_INTERACTION);
    }

    @Test
    void deliveryGateIsOnlyRequiredForExplicitCodingRuns() {
        assertThat(RunExecutionMode.CONVERSATIONAL.requiresDeliveryGate()).isFalse();
        assertThat(RunExecutionMode.NODE_INTERACTION.requiresDeliveryGate()).isFalse();
        assertThat(RunExecutionMode.CODING.requiresDeliveryGate()).isTrue();
    }

    @Test
    void ordinaryRequestsWithoutAnExplicitNodeStayConversational() {
        assertThat(RunExecutionMode.from(command("Create a snake game", List.of(), null, null)))
                .isEqualTo(RunExecutionMode.CONVERSATIONAL);
    }

    @Test
    void automaticNodeMarkerDoesNotSelectAnExecutor() {
        assertThat(RunExecutionMode.from(command("Create a snake game", List.of(), "auto", null)))
                .isEqualTo(RunExecutionMode.CONVERSATIONAL);
    }

    private static CreateRunCommand command(String text, List<String> tools, String nodeId, String workingDirectory) {
        return new CreateRunCommand(
                "conversation-1", text, "model-1", "agent-1", List.of(), List.of(), List.of(), tools,
                nodeId, workingDirectory);
    }
}
