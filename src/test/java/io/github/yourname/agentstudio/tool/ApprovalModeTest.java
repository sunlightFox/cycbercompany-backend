package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalModeTest {

    private static final ResolvedToolBinding MEDIUM_RISK = binding(RiskLevel.MEDIUM);
    private static final ResolvedToolBinding HIGH_RISK = binding(RiskLevel.HIGH);

    @Test
    void defaultsToOnRequestAndAcceptsStableWireValues() {
        assertThat(ApprovalMode.from(null)).isEqualTo(ApprovalMode.ON_REQUEST);
        assertThat(ApprovalMode.from("auto-approve")).isEqualTo(ApprovalMode.AUTO_APPROVE);
        assertThat(ApprovalMode.from("FULL_ACCESS")).isEqualTo(ApprovalMode.FULL_ACCESS);
        assertThatThrownBy(() -> ApprovalMode.from("unlimited"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported approval mode");
    }

    @Test
    void approvalPoliciesBecomeLessInteractiveOnlyAfterAnExplicitSelection() {
        assertThat(ApprovalMode.ON_REQUEST.requiresApproval(MEDIUM_RISK)).isTrue();
        assertThat(ApprovalMode.AUTO_APPROVE.requiresApproval(MEDIUM_RISK)).isFalse();
        assertThat(ApprovalMode.AUTO_APPROVE.requiresApproval(HIGH_RISK)).isTrue();
        assertThat(ApprovalMode.FULL_ACCESS.requiresApproval(HIGH_RISK)).isFalse();
    }

    @Test
    void pickerMetadataKeepsAuditEnabledForEveryMode() {
        assertThat(ApprovalMode.options())
                .extracting(ApprovalMode.ApprovalModeOption::id)
                .containsExactly("on-request", "auto-approve", "full-access");
        assertThat(ApprovalMode.options())
                .allMatch(ApprovalMode.ApprovalModeOption::auditEnabled);
    }

    private static ResolvedToolBinding binding(RiskLevel riskLevel) {
        return new ResolvedToolBinding(
                "node:node-1:fs.write",
                "tool_fs_write",
                "fs.write",
                "node",
                "fs.write",
                "Write a file",
                riskLevel,
                true,
                Map.of(),
                Map.of());
    }
}
