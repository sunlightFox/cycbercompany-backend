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
        List<String> attachmentIds) {

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
                List.of());
    }
}
