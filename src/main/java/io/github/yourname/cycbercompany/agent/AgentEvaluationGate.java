package io.github.yourname.cycbercompany.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentEvaluationGate {

    private final AgentEvaluationRepository evaluations;

    public AgentEvaluationGate(AgentEvaluationRepository evaluations) {
        this.evaluations = evaluations;
    }

    public void verify(AgentVersionEntity version, JsonNode manifest) {
        JsonNode policy = manifest.path("evaluation");
        if (!policy.path("requiredBeforePublish").asBoolean(false)) {
            return;
        }
        double minimumPassRate = policy.path("minimumPassRate").asDouble(1.0);
        List<String> problems = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (JsonNode suiteNode : policy.path("suiteIds")) {
            String suiteId = suiteNode.asText();
            var result = evaluations.findTopByTenantIdAndVersionIdAndManifestDigestAndSuiteIdOrderByCreatedAtDesc(
                    version.tenantId(), version.id(), version.manifestDigest(), suiteId);
            if (result.isEmpty()) {
                problems.add("Missing evaluation for suite " + suiteId + " and the current manifest digest.");
            } else {
                scores.add(result.get().score());
            }
        }
        double score = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (problems.isEmpty() && score < minimumPassRate) {
            problems.add("Evaluation pass rate " + score + " is below required minimum " + minimumPassRate + ".");
        }
        if (!problems.isEmpty()) {
            throw new AgentEvaluationRequiredException(problems);
        }
    }
}
