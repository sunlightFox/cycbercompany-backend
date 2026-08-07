package io.github.yourname.agentstudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentEvaluationService {

    private final AgentIdentityRepository identities;
    private final AgentVersionRepository versions;
    private final AgentEvaluationRepository evaluations;
    private final AgentEvaluationSuiteRegistry suites;
    private final AgentDraftTestService draftTests;
    private final ObjectMapper objectMapper;

    public AgentEvaluationService(
            AgentIdentityRepository identities,
            AgentVersionRepository versions,
            AgentEvaluationRepository evaluations,
            AgentEvaluationSuiteRegistry suites,
            AgentDraftTestService draftTests,
            ObjectMapper objectMapper) {
        this.identities = identities;
        this.versions = versions;
        this.evaluations = evaluations;
        this.suites = suites;
        this.draftTests = draftTests;
        this.objectMapper = objectMapper;
    }

    public AgentEvaluationReportView evaluate(
            String agentId,
            String versionId,
            String tenantId,
            String userId) {
        AgentIdentityEntity identity = identities.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!identity.ownerUserId().equals(userId)) {
            throw new IllegalArgumentException("Only the Agent owner can evaluate its draft: " + agentId);
        }
        AgentVersionEntity version = versions.findByIdAndAgentIdAndTenantId(versionId, agentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Agent version not found: " + versionId));
        if (version.state() != AgentVersionState.DRAFT) {
            throw new IllegalArgumentException("Only a draft Agent version can be evaluated: " + versionId);
        }
        JsonNode manifest = parse(version.manifestJson());
        JsonNode evaluation = manifest.path("evaluation");
        List<String> suiteIds = new ArrayList<>();
        evaluation.path("suiteIds").forEach(value -> suiteIds.add(value.asText()));
        if (suiteIds.isEmpty()) {
            throw new IllegalArgumentException("The Agent manifest does not configure evaluation suites.");
        }

        Instant evaluatedAt = Instant.now();
        List<AgentEvaluationReportView.SuiteResult> suiteResults = suiteIds.stream()
                .map(suiteId -> runSuite(
                        suites.require(suiteId), agentId, versionId, version.manifestDigest(), tenantId, userId,
                        evaluatedAt))
                .toList();
        double score = suiteResults.stream().mapToDouble(AgentEvaluationReportView.SuiteResult::score)
                .average().orElse(1.0);
        double minimumPassRate = evaluation.path("minimumPassRate").asDouble(1.0);
        return new AgentEvaluationReportView(
                agentId,
                versionId,
                version.manifestDigest(),
                score,
                score >= minimumPassRate,
                evaluatedAt,
                suiteResults);
    }

    private AgentEvaluationReportView.SuiteResult runSuite(
            AgentEvaluationSuiteRegistry.SuiteDefinition suite,
            String agentId,
            String versionId,
            String manifestDigest,
            String tenantId,
            String userId,
            Instant evaluatedAt) {
        List<AgentEvaluationReportView.CaseResult> cases = suite.cases().stream()
                .map(testCase -> runCase(suite.id(), testCase, agentId, versionId, tenantId, userId))
                .toList();
        double score = cases.stream().filter(AgentEvaluationReportView.CaseResult::passed).count()
                / (double) cases.size();
        var result = new AgentEvaluationReportView.SuiteResult(
                suite.id(), score, score == 1.0, cases);
        evaluations.save(new AgentEvaluationEntity(
                UUID.randomUUID().toString(),
                tenantId,
                agentId,
                versionId,
                manifestDigest,
                suite.id(),
                score,
                result.passed(),
                json(result),
                userId,
                evaluatedAt));
        return result;
    }

    private AgentEvaluationReportView.CaseResult runCase(
            String suiteId,
            AgentEvaluationSuiteRegistry.CaseDefinition testCase,
            String agentId,
            String versionId,
            String tenantId,
            String userId) {
        try {
            AgentDraftTestView result = draftTests.test(
                    agentId,
                    versionId,
                    new AgentDraftTestCommand(
                            List.of(new AgentDraftTestMessage("USER", testCase.prompt())), null),
                    tenantId,
                    userId);
            if (result.toolCallsBlocked()) {
                return failed(testCase.id(), "The model requested a tool in the tool-free evaluation sandbox.");
            }
            String content = result.content() == null ? "" : result.content().trim();
            if (content.length() < 8) {
                return failed(testCase.id(), "The model returned no usable answer.");
            }
            String normalized = content.toLowerCase(Locale.ROOT);
            for (String forbidden : testCase.forbiddenSubstrings()) {
                if (normalized.contains(forbidden.toLowerCase(Locale.ROOT))) {
                    return failed(testCase.id(), "The answer exposed a forbidden internal marker.");
                }
            }
            return new AgentEvaluationReportView.CaseResult(testCase.id(), true, "Passed smoke checks.");
        } catch (Exception ex) {
            return failed(testCase.id(), "Evaluation call failed: " + safeMessage(ex));
        }
    }

    private static AgentEvaluationReportView.CaseResult failed(String caseId, String reason) {
        return new AgentEvaluationReportView.CaseResult(caseId, false, reason);
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored Agent manifest is unreadable.", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize Agent evaluation report.", ex);
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
