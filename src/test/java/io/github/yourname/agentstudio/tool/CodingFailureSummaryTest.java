package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证常见 Java 与前端测试输出会成为可直接检索的失败线索。 */
class CodingFailureSummaryTest {

    @Test
    void extractsSourceLocationsAndGradleTestNames() {
        Map<String, Object> summary = CodingFailureSummary.from(
                "shell.run",
                false,
                Map.of("exitCode", 1, "stderr", "src/main/java/demo/Calculator.java:42: error: cannot find symbol\n", "stdout", "CalculatorTest.addsNumbers() FAILED\n"),
                "Command exited with code 1.");

        assertThat(summary).containsEntry("kind", "command_failure");
        assertThat(summary.get("failedTests")).isEqualTo(List.of("CalculatorTest.addsNumbers()"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locations = (List<Map<String, Object>>) summary.get("sourceLocations");
        assertThat(locations).containsExactly(Map.of("path", "src/main/java/demo/Calculator.java", "line", 42));
        assertThat(summary.get("suggestedSearchTerms"))
                .isEqualTo(List.of("src/main/java/demo/Calculator.java", "CalculatorTest.addsNumbers()"));
    }

    @Test
    void recognizesJestFailuresAndLeavesSuccessfulCommandsUntouched() {
        Map<String, Object> failure = CodingFailureSummary.from(
                "shell.run", false, Map.of("stdout", "FAIL src/App.test.tsx\n"), "Command exited with code 1.");

        assertThat(failure.get("failedTests")).isEqualTo(List.of("src/App.test.tsx"));
        assertThat(CodingFailureSummary.from("shell.run", true, Map.of(), null)).isEmpty();
        assertThat(CodingFailureSummary.from("fs.read", false, Map.of(), "failed")).isEmpty();
    }

    @Test
    void identifiesMissingToolchainsAsEnvironmentPrerequisites() {
        Map<String, Object> summary = CodingFailureSummary.from(
                "shell.run",
                false,
                Map.of("stderr", "mvn : The term 'mvn' is not recognized as the name of a cmdlet.\n"),
                "Command exited with code 1.");

        assertThat(summary).containsEntry("kind", "missing_prerequisite");
        assertThat(summary.get("nextStep").toString())
                .contains("project-local wrapper")
                .contains("rerun the original command");
    }
}
