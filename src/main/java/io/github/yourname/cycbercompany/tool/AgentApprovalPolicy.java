package io.github.yourname.cycbercompany.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable Agent-level approval policy captured in each RunSpec. */
public record AgentApprovalPolicy(String preset, List<Rule> rules) {

    public enum Decision {
        ALLOW,
        ASK,
        DENY
    }

    public record Rule(RiskLevel riskLevel, Decision decision) {
        public Rule {
            if (riskLevel == null || decision == null) {
                throw new IllegalArgumentException("Agent approval rules require a risk level and decision.");
            }
        }
    }

    public AgentApprovalPolicy {
        preset = preset == null || preset.isBlank() ? "SESSION_ONLY" : preset.trim().toUpperCase(Locale.ROOT);
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (!List.of("SESSION_ONLY", "CONSERVATIVE", "BALANCED", "CUSTOM").contains(preset)) {
            throw new IllegalArgumentException("Unsupported Agent approval preset: " + preset);
        }
        if ("CUSTOM".equals(preset) && rules.isEmpty()) {
            throw new IllegalArgumentException("CUSTOM Agent approval policy requires at least one rule.");
        }
        Map<RiskLevel, Decision> unique = new EnumMap<>(RiskLevel.class);
        for (Rule rule : rules) {
            if (unique.putIfAbsent(rule.riskLevel(), rule.decision()) != null) {
                throw new IllegalArgumentException(
                        "Agent approval policy contains duplicate risk level: " + rule.riskLevel());
            }
        }
    }

    /** Preserves behavior for legacy Agents and old RunSpec snapshots. */
    public static AgentApprovalPolicy sessionOnly() {
        return new AgentApprovalPolicy("SESSION_ONLY", List.of());
    }

    public static AgentApprovalPolicy fromManifest(JsonNode safety) {
        if (safety == null || safety.isMissingNode() || safety.isNull()) {
            return sessionOnly();
        }
        String preset = safety.path("approvalPreset").asText("SESSION_ONLY");
        List<Rule> rules = new ArrayList<>();
        safety.path("customApprovalRules").forEach(rule -> rules.add(new Rule(
                RiskLevel.valueOf(rule.path("riskLevel").asText()),
                Decision.valueOf(rule.path("decision").asText()))));
        return new AgentApprovalPolicy(preset, rules);
    }

    public Decision decisionFor(ResolvedToolBinding binding) {
        RiskLevel risk = binding == null || binding.riskLevel() == null ? RiskLevel.HIGH : binding.riskLevel();
        return switch (preset) {
            case "SESSION_ONLY" -> Decision.ALLOW;
            case "CONSERVATIVE" -> risk == RiskLevel.LOW ? Decision.ALLOW : Decision.ASK;
            case "BALANCED" -> risk.ordinal() < RiskLevel.HIGH.ordinal() ? Decision.ALLOW : Decision.ASK;
            case "CUSTOM" -> rules.stream()
                    .filter(rule -> rule.riskLevel() == risk)
                    .map(Rule::decision)
                    .findFirst()
                    .map(AgentApprovalPolicy::approvalDecision)
                    .orElse(Decision.ASK);
            default -> throw new IllegalStateException("Unsupported Agent approval preset: " + preset);
        };
    }

    private static Decision approvalDecision(Decision decision) {
        return decision == Decision.DENY ? Decision.ASK : decision;
    }
}
