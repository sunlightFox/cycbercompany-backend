package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.knowledge.EvidenceBundle;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCommandSystemPromptTest {

    @Test
    void codingRunsReceiveWorkspaceScopeAndVerificationWorkflow() {
        CreateRunCommand command = new CreateRunCommand(
                "conversation-1",
                "Create a project in task-board.",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "node-1",
                "task-board");

        String prompt = RunCommandService.buildSystemPrompt(
                "You are a coding assistant.",
                command,
                new EvidenceBundle(List.of()),
                List.of(),
                List.of(),
                "",
                "");

        assertThat(prompt)
                .contains("target directory")
                .contains("only project scope")
                .contains("unrelated samples")
                .contains("project.inspect")
                .contains("manifest-backed recommendations")
                .contains("managed development process")
                .contains("fs.search")
                .contains("startLine")
                .contains("finite tool budget")
                .contains("Project scope for this run: task-board");
    }
}
