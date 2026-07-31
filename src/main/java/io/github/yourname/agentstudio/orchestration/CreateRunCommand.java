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
        String nodeId) {
}
