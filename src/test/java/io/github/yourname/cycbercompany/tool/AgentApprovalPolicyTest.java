package io.github.yourname.cycbercompany.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentApprovalPolicyTest {

    @Test
    void presetsApplyRiskBasedDefaults() {
        AgentApprovalPolicy conservative = new AgentApprovalPolicy("CONSERVATIVE", List.of());
        AgentApprovalPolicy balanced = new AgentApprovalPolicy("BALANCED", List.of());

        assertThat(conservative.decisionFor(binding(RiskLevel.LOW)))
                .isEqualTo(AgentApprovalPolicy.Decision.ALLOW);
        assertThat(conservative.decisionFor(binding(RiskLevel.MEDIUM)))
                .isEqualTo(AgentApprovalPolicy.Decision.ASK);
        assertThat(balanced.decisionFor(binding(RiskLevel.MEDIUM)))
                .isEqualTo(AgentApprovalPolicy.Decision.ALLOW);
        assertThat(balanced.decisionFor(binding(RiskLevel.HIGH)))
                .isEqualTo(AgentApprovalPolicy.Decision.ASK);
        assertThat(balanced.decisionFor(binding(RiskLevel.CRITICAL)))
                .isEqualTo(AgentApprovalPolicy.Decision.ASK);
    }

    @Test
    void customPolicyConvertsLegacyDenialsToApprovalRequests() {
        AgentApprovalPolicy policy = new AgentApprovalPolicy("CUSTOM", List.of(
                new AgentApprovalPolicy.Rule(RiskLevel.HIGH, AgentApprovalPolicy.Decision.DENY)));

        assertThat(policy.decisionFor(binding(RiskLevel.HIGH)))
                .isEqualTo(AgentApprovalPolicy.Decision.ASK);
        assertThat(policy.decisionFor(binding(RiskLevel.LOW)))
                .isEqualTo(AgentApprovalPolicy.Decision.ASK);
    }

    @Test
    void customPolicyRejectsDuplicateRiskLevels() {
        assertThatThrownBy(() -> new AgentApprovalPolicy("CUSTOM", List.of(
                        new AgentApprovalPolicy.Rule(RiskLevel.HIGH, AgentApprovalPolicy.Decision.ASK),
                        new AgentApprovalPolicy.Rule(RiskLevel.HIGH, AgentApprovalPolicy.Decision.DENY))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate risk level");
    }

    private static ResolvedToolBinding binding(RiskLevel riskLevel) {
        return new ResolvedToolBinding(
                "node:1:test", "tool_test", "test", "node", "test", "Test", riskLevel,
                true, Map.of(), Map.of());
    }
}
