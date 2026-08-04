package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.tool.ApprovalMode;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateRunCommand(
        @NotBlank String conversationId,
        @NotBlank String text,
        String modelProfileId,
        String agentId,
        List<String> knowledgeBaseIds,
        List<String> skillIds,
        List<String> mcpServerIds,
        List<String> toolNames,
        String nodeId,
        String workingDirectory,
        List<String> attachmentIds,
        List<String> nodeLabels,
        String approvalMode) {

    public CreateRunCommand {
        approvalMode = ApprovalMode.from(approvalMode).wireValue();
        nodeLabels = nodeLabels == null ? List.of() : List.copyOf(nodeLabels);
    }

    /** Compatibility constructor used by clients created before sandbox label routing was added. */
    public CreateRunCommand(
            String conversationId,
            String text,
            String modelProfileId,
            String agentId,
            List<String> knowledgeBaseIds,
            List<String> skillIds,
            List<String> mcpServerIds,
            List<String> toolNames,
            String nodeId,
            String workingDirectory,
            List<String> attachmentIds) {
        this(
                conversationId,
                text,
                modelProfileId,
                agentId,
                knowledgeBaseIds,
                skillIds,
                mcpServerIds,
                toolNames,
                nodeId,
                workingDirectory,
                attachmentIds,
                List.of(),
                ApprovalMode.ON_REQUEST.wireValue());
    }

    /** Compatibility constructor for clients created before approval mode selection was added. */
    public CreateRunCommand(
            String conversationId,
            String text,
            String modelProfileId,
            String agentId,
            List<String> knowledgeBaseIds,
            List<String> skillIds,
            List<String> mcpServerIds,
            List<String> toolNames,
            String nodeId,
            String workingDirectory,
            List<String> attachmentIds,
            List<String> nodeLabels) {
        this(
                conversationId,
                text,
                modelProfileId,
                agentId,
                knowledgeBaseIds,
                skillIds,
                mcpServerIds,
                toolNames,
                nodeId,
                workingDirectory,
                attachmentIds,
                nodeLabels,
                ApprovalMode.ON_REQUEST.wireValue());
    }

    public CreateRunCommand(
            String conversationId,
            String text,
            String modelProfileId,
            String agentId,
            List<String> knowledgeBaseIds,
            List<String> skillIds,
            List<String> mcpServerIds,
            List<String> toolNames,
            String nodeId,
            String workingDirectory) {
        this(
                conversationId,
                text,
                modelProfileId,
                agentId,
                knowledgeBaseIds,
                skillIds,
                mcpServerIds,
                toolNames,
                nodeId,
                workingDirectory,
                List.of(),
                List.of(),
                ApprovalMode.ON_REQUEST.wireValue());
    }
}
