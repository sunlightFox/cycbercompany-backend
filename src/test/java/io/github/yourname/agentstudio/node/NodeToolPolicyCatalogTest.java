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

    @Test
    void keepsProjectDiagnosticsReadOnlyAndEnabledByDefault() {
        // 诊断工具只解析调用方提交的日志文本，不执行命令、不读文件，因此与项目导航工具一致。
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("project.diagnose");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void keepsCandidateReferenceSearchReadOnlyAndEnabledByDefault() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("project.references");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void keepsGitReviewReadOnlyAndEnabledByDefault() {
        // 该工具只读取 Git porcelain 状态，不能暂存、提交或修改工作树。
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("git.review");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void keepsManagedProcessLogsReadOnlyAndEnabledByDefault() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("process.logs");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void keepsManagedLoopbackHttpReadinessReadOnlyAndEnabledByDefault() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("process.wait_http");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void keepsBrowserVerificationReadOnlyAndEnabledByDefault() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("browser.verify");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void keepsPostActionBrowserResponseWaitingReadOnlyAndEnabledByDefault() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("browser.wait_response");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isFalse();
    }

    @Test
    void keepsBrowserTabClosureApprovalProtected() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("browser.close_tab");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isTrue();
    }

    @Test
    void keepsDesktopUiVerificationApprovalProtected() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("system.desktop.ui.verify");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isTrue();
    }

    @Test
    void keepsDesktopUiWaitApprovalProtected() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("system.desktop.ui.wait");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isTrue();
    }

    @Test
    void keepsDesktopScreenshotApprovalProtected() {
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("system.desktop.screenshot");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isTrue();
    }

    @Test
    void keepsDesktopUiValueReadApprovalProtected() {
        // 普通控件值也可能是个人或业务数据，只有用户明确审批后才能读取。
        NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor("system.desktop.ui.read_value");

        assertThat(policy.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.enabledByDefault()).isTrue();
        assertThat(policy.requiresApproval()).isTrue();
    }
}
