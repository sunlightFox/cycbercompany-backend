package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.tool.RiskLevel;
import org.junit.jupiter.api.Test;

class NodeToolPolicyCatalogTest {

    @Test
    void keepsDesktopWallpaperAsAnApprovalProtectedSystemOperation() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("system.desktop.set_wallpaper");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isTrue();
    }

    @Test
    void failsClosedForAnUnknownNodeCapability() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("system.unknown.operation");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.enabledByDefault()).isFalse();
        assertThat(policy.requiresApproval()).isTrue();
    }
}
