package io.github.yourname.agentstudio.skill;

/** Run 准备阶段的结构化兼容失败；此异常发生时 Run 尚未入库或调用模型。 */
public class SkillCompatibilityException extends IllegalArgumentException {

    private final CompatibilityReport report;

    public SkillCompatibilityException(CompatibilityReport report) {
        super(message(report));
        this.report = report;
    }

    public CompatibilityReport report() {
        return report;
    }

    private static String message(CompatibilityReport report) {
        if (report == null || report.issues().isEmpty()) {
            return "Skill compatibility check failed.";
        }
        return "Skill compatibility check failed: " + report.issues().stream()
                .filter(issue -> "ERROR".equals(issue.severity()))
                .map(CompatibilityReport.Issue::message)
                .distinct()
                .reduce((left, right) -> left + "; " + right)
                .orElse("unknown compatibility error");
    }
}
