package io.github.yourname.agentstudio.orchestration;

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
        List<String> nodeLabels) {

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
                List.of());
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
                List.of());
    }
}
