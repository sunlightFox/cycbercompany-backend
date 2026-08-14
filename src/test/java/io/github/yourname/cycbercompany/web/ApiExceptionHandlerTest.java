package io.github.yourname.cycbercompany.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.cycbercompany.conversation.ConversationArchivedException;
import io.github.yourname.cycbercompany.node.LocalComputerControlNotReadyException;
import io.github.yourname.cycbercompany.skill.CompatibilityReport;
import io.github.yourname.cycbercompany.skill.SkillCompatibilityException;
import org.junit.jupiter.api.Test;
import java.util.List;

class ApiExceptionHandlerTest {

    @Test
    void exposesCompatibilityIssuesAsStructuredUnprocessableContent() {
        CompatibilityReport report = new CompatibilityReport(false,
                List.of(new CompatibilityReport.Issue("ERROR", "MISSING_TOOL", "skill", "Tool is missing.")),
                List.of("fs.read"), List.of(), List.of());

        var response = new ApiExceptionHandler().skillCompatibility(new SkillCompatibilityException(report));

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getProperties()).containsEntry("code", "SKILL_INCOMPATIBLE").containsEntry("report", report);
    }

    @Test
    void exposesArchivedConversationConflictsAsStructuredConflict() {
        var response = new ApiExceptionHandler().conversationArchived(new ConversationArchivedException("conversation-1"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getProperties()).containsEntry("code", "CONVERSATION_ARCHIVED");
    }

    @Test
    void exposesLocalCompanionRecoveryAsStructuredBadRequest() {
        var response = new ApiExceptionHandler().localComputerControlNotReady(
                new LocalComputerControlNotReadyException());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getProperties())
                .containsEntry("code", "LOCAL_COMPUTER_CONTROL_NOT_READY");
    }
}
