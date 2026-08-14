package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.cycbercompany.node.CodingRunEvidenceView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

/** 验证交付门禁只根据服务端证据决定结果。 */
class CodingDeliveryGateTest {

    private final CodingDeliveryGate gate = new CodingDeliveryGate();

    @Test
    void passesChangedBackendCodeAfterSuccessfulCommandVerification() {
        CodingDeliveryGate.Decision decision = gate.evaluate(evidence(
                List.of("src/App.java"), List.of("shell.run"), List.of("test"), List.of(), false, List.of()));

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.PASS);
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void blocksChangedFilesWithoutVerificationCommand() {
        CodingDeliveryGate.Decision decision = gate.evaluate(evidence(
                List.of("src/App.java"), List.of(), List.of(), List.of(), false, List.of()));

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(decision.reasons()).contains("已修改项目文件，但没有成功的构建、测试或命令验证证据。");
    }

    @Test
    void blocksBrowserInteractionWithoutReplayableTrace() {
        CodingDeliveryGate.Decision decision = gate.evaluate(evidence(
                List.of("web/App.tsx"), List.of("shell.run", "browser.open", "browser.click"), List.of("test"), List.of(), true, List.of()));

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(decision.reasons()).contains("已执行浏览器页面交互，但没有可回放的浏览器 Trace 证据。");
    }

    @Test
    void blocksBrowserInteractionWithoutAnExplicitPostInteractionVerification() {
        CodingDeliveryGate.Decision decision = gate.evaluate(evidence(
                List.of("web/App.tsx"), List.of("shell.run", "browser.open", "browser.click"), List.of("test"), List.of(), false,
                List.of("browser-trace.zip")));

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(decision.reasons()).contains("已执行浏览器页面交互，但最后一次交互后没有成功的 browser.verify 证据。");
    }

    @Test
    void blocksDesktopUiActionsWithoutAPostActionControlVerification() {
        CodingRunEvidenceView evidence = new CodingRunEvidenceView(
                "run-1", 1, List.of("system.desktop.ui.click"), -1,
                List.of(), false, List.of(), List.of(), List.of(), List.of(), false, false, List.of());

        CodingDeliveryGate.Decision decision = gate.evaluate(evidence);

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("system.desktop.ui.verify"));
    }

    @Test
    void acceptsDesktopUiActionsWithAPostActionControlVerification() {
        CodingRunEvidenceView evidence = new CodingRunEvidenceView(
                "run-1", 2, List.of("system.desktop.ui.type", "system.desktop.ui.verify"), -1,
                List.of(), false, List.of(), List.of(), List.of(), List.of(), false, true, List.of());

        CodingDeliveryGate.Decision decision = gate.evaluate(evidence);

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.PASS);
    }

    @Test
    void acceptsAnApprovedDesktopUiValueReadAsPostActionEvidence() {
        CodingRunEvidenceView evidence = new CodingRunEvidenceView(
                "run-1", 2, List.of("system.desktop.ui.type", "system.desktop.ui.read_value"), -1,
                List.of(), false, List.of(), List.of(), List.of(), List.of(), false, true, List.of());

        CodingDeliveryGate.Decision decision = gate.evaluate(evidence);

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.PASS);
    }

    @Test
    void blocksDesktopApplicationStartUntilASnapshotObservesItsWindow() {
        CodingRunEvidenceView unobserved = new CodingRunEvidenceView(
                "run-1", 1, List.of("system.desktop.application.start"), -1,
                List.of(), false, List.of(), List.of(), List.of(), List.of(), false, false, List.of(), false, false);
        CodingRunEvidenceView observed = new CodingRunEvidenceView(
                "run-1", 2, List.of("system.desktop.application.start", "system.desktop.session.snapshot"), -1,
                List.of(), false, List.of(), List.of(), List.of(), List.of(), false, false, List.of(), false, true);

        CodingDeliveryGate.Decision rejected = gate.evaluate(unobserved);
        CodingDeliveryGate.Decision accepted = gate.evaluate(observed);

        assertThat(rejected.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(rejected.reasons()).contains("已启动桌面应用，但后续 system.desktop.session.snapshot 未确认对应 PID 的可见窗口。");
        assertThat(accepted.status()).isEqualTo(CodingDeliveryGate.Status.PASS);
    }

    @Test
    void blocksARecoveredRunUntilTheFailureIsExplicitlyResolvedInANewRun() {
        CodingDeliveryGate.Decision decision = gate.evaluate(evidence(
                List.of("src/App.java"), List.of("shell.run"), List.of("test"), List.of("shell.run"), false, List.of()));

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(decision.reasons()).contains("本次运行存在失败的节点工具调用，需先修复并重新验证。");
    }

    @Test
    void allowsReadOnlyAnalysisWithoutACommandVerificationRequirement() {
        CodingDeliveryGate.Decision decision = gate.evaluate(evidence(
                List.of(), List.of("fs.read"), List.of(), List.of(), false, List.of()));

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.PASS);
    }

    @Test
    void blocksChangedFilesWithoutPostChangeGitReview() {
        CodingRunEvidenceView evidence = new CodingRunEvidenceView(
                "run-1", 2, List.of(), -1, List.of("src/App.java"), false, List.of(),
                List.of("shell.run"), List.of("test"), List.of(), false, List.of());

        CodingDeliveryGate.Decision decision = gate.evaluate(evidence);

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(decision.reasons()).contains("已修改项目文件，但最后一次修改后没有成功的 Git 变更审阅证据。")
                .contains("已修改的项目文件尚未全部在最后一次修改后通过 git.diff 或 fs.read 审阅。");
    }

    @Test
    void blocksWhenOneChangedFileWasNotReviewed() {
        CodingRunEvidenceView evidence = new CodingRunEvidenceView(
                "run-1", 4, List.of("git.review", "fs.read"), -1,
                List.of("src/App.java", "src/Config.java"), true, List.of("src/App.java"),
                List.of("shell.run"), List.of("test"), List.of(), false, List.of());

        CodingDeliveryGate.Decision decision = gate.evaluate(evidence);

        assertThat(decision.status()).isEqualTo(CodingDeliveryGate.Status.NEEDS_VERIFICATION);
        assertThat(decision.reasons()).contains("已修改的项目文件尚未全部在最后一次修改后通过 git.diff 或 fs.read 审阅。");
    }

    private static CodingRunEvidenceView evidence(
            List<String> changedFiles,
            List<String> verificationTools,
            List<String> commandVerifications,
            List<String> failedTools,
            boolean browserVerified,
            List<String> traceArtifacts) {
        boolean changed = !changedFiles.isEmpty();
        return new CodingRunEvidenceView(
                "run-1", 1, changed ? List.of("git.review", "fs.read") : List.of(), -1,
                changedFiles, changed, changedFiles, verificationTools, commandVerifications,
                traceArtifacts, browserVerified, failedTools);
    }

    private static CodingRunEvidenceView fullStackEvidence(boolean browserApiVerified) {
        return new CodingRunEvidenceView(
                "run-full-stack",
                6,
                List.of("fs.write", "shell.run", "browser.open", "browser.click", "browser.verify", "browser.trace.stop"),
                -1,
                List.of("web/App.tsx"),
                true,
                List.of("web/App.tsx"),
                List.of("shell.run", "browser.open", "browser.click", "browser.verify", "browser.trace.stop"),
                List.of("test"),
                List.of("trace.zip"),
                true,
                false,
                List.of(),
                browserApiVerified);
    }
}
