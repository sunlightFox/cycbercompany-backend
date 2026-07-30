package io.github.yourname.agentstudio.knowledge;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record KnowledgeSearchCommand(List<String> knowledgeBaseIds, @NotBlank String query, int limit) {
}
