package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.node.CodingRunEvidenceView;
import java.util.List;
import org.junit.jupiter.api.Test;

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
        assertThat(decision.reasons()).contains("已执行浏览器输入或点击，但没有可回放的浏览器 Trace 证据。");
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

    private static CodingRunEvidenceView evidence(
            List<String> changedFiles,
            List<String> verificationTools,
            List<String> commandVerifications,
            List<String> failedTools,
            boolean browserVerified,
            List<String> traceArtifacts) {
        return new CodingRunEvidenceView(
                "run-1", 1, changedFiles, verificationTools, commandVerifications, traceArtifacts, browserVerified, failedTools);
    }
}
