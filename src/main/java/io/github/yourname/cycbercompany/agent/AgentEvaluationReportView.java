package io.github.yourname.cycbercompany.agent;

import java.time.Instant;
import java.util.List;

public record AgentEvaluationReportView(
        String agentId,
        String versionId,
        String manifestDigest,
        double score,
        boolean passed,
        Instant evaluatedAt,
        List<SuiteResult> suites) {

    public AgentEvaluationReportView {
        suites = suites == null ? List.of() : List.copyOf(suites);
    }

    public record SuiteResult(
            String suiteId,
            double score,
            boolean passed,
            List<CaseResult> cases) {
        public SuiteResult {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public record CaseResult(
            String caseId,
            boolean passed,
            String reason) {
    }
}
