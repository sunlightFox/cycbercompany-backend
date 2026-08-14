package io.github.yourname.cycbercompany.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NodeToolPolicyCatalogTest {

    @Test
    void managedLocalPolicyEnablesEvenHighRiskCapabilitiesWithoutApproval() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.managedLocalPolicyFor("system.software.uninstall");

        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void registeredNodePolicyRemainsApprovalGated() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("system.software.uninstall");

        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isTrue();
    }
}
