package io.github.yourname.agentstudio.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.skill.CompatibilityReport;
import io.github.yourname.agentstudio.skill.SkillCompatibilityException;
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
}
