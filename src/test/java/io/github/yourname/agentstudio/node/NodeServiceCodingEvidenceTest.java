package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证“交付摘要”只显示用户真正需要的结果，且面对不可信历史记录时仍保持安全。
 */
@ExtendWith(MockitoExtension.class)
class NodeServiceCodingEvidenceTest {

    private static final ActorContext ACTOR = new ActorContext("tenant-a", "user-a", Set.of(), Set.of());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private NodeConnectionRepository nodes;
    @Mock
    private NodeRegistrationTokenRepository tokens;
    @Mock
    private NodeToolRepository tools;
    @Mock
    private NodeToolInvocationRepository invocations;
    @Mock
    private NodeToolApprovalRepository approvals;
    @Mock
    private NodeSessionRegistry sessions;

    @Test
    void summarizesChangedFilesAndVerificationWithoutExposingInvocationPayloads() {
        // 同一个文件先写入、后打补丁，只应在“修改文件”中出现一次。
        NodeToolInvocationEntity write = successful("fs.write", "{\"path\":\"projects/app/src/App.java\"}");
        NodeToolInvocationEntity patch = successful("fs.apply_patch", "{\"path\":\"projects/app/src/App.java\"}");
        NodeToolInvocationEntity command = successful("shell.run", "{\"command\":\"./gradlew test\"}");
        NodeToolInvocationEntity browser = successful("browser.open", "{\"url\":\"http://localhost:8080\"}");
        NodeToolInvocationEntity trace = successful("browser.trace.stop", "{}");
        trace.succeed("{\"path\":\"C:\\\\Users\\\\node\\\\AppData\\\\Local\\\\Temp\\\\browser-2026.zip\"}", NOW);
        NodeToolInvocationEntity review = successful("git.review", "{}");
        NodeToolInvocationEntity read = successful("fs.read", "{\"path\":\"projects/app/src/App.java\"}");
        NodeToolInvocationEntity browserVerify = successful("browser.verify", "{\"checks\":[]}");
        NodeToolInvocationEntity failedRead = failed("fs.read");

        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(write, patch, command, browser, trace, review, read, browserVerify, failedRead));

        CodingRunEvidenceView evidence = service().codingEvidence("run-a", ACTOR);

        assertThat(evidence.runId()).isEqualTo("run-a");
        assertThat(evidence.toolCalls()).isEqualTo(9);
        assertThat(evidence.succeededTools()).containsExactly(
                "fs.write", "fs.apply_patch", "shell.run", "browser.open", "browser.trace.stop", "git.review", "fs.read", "browser.verify");
        assertThat(evidence.changedFiles()).containsExactly("projects/app/src/App.java");
        assertThat(evidence.gitReviewed()).isTrue();
        assertThat(evidence.reviewedChangedFiles()).containsExactly("projects/app/src/App.java");
        assertThat(evidence.verificationTools()).containsExactly("shell.run", "browser.open", "browser.trace.stop", "browser.verify");
        assertThat(evidence.commandVerifications()).containsExactly("test");
        assertThat(evidence.browserTraceArtifacts()).containsExactly("browser-2026.zip");
        assertThat(evidence.browserVerified()).isTrue();
        assertThat(evidence.failedTools()).containsExactly("fs.read");
    }

    @Test
    void doesNotTreatOpeningAPageAsACompletedBrowserVerification() {
        NodeToolInvocationEntity open = successful("browser.open", "{\"url\":\"http://localhost:8080\"}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(open));

        CodingRunEvidenceView evidence = service().codingEvidence("run-a", ACTOR);

        assertThat(evidence.verificationTools()).containsExactly("browser.open");
        assertThat(evidence.browserVerified()).isFalse();
    }

    @Test
    void distinguishesPostInteractionApiEvidenceFromOrdinaryPageAssertions() {
        NodeToolInvocationEntity open = successful("browser.open", "{\"url\":\"http://localhost:8080\"}");
        NodeToolInvocationEntity apiVerify = successful("browser.verify", "{\"checks\":[]}");
        apiVerify.succeed("""
                {"verified":true,"checks":[
                  {"type":"textContains","passed":true},
                  {"type":"responseStatus","passed":true}
                ]}
                """, NOW);
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(open, apiVerify));

        CodingRunEvidenceView apiEvidence = service().codingEvidence("run-a", ACTOR);

        assertThat(apiEvidence.browserVerified()).isTrue();
        assertThat(apiEvidence.browserApiVerified()).isTrue();

        NodeToolInvocationEntity visibleOnlyVerify = successful("browser.verify", "{\"checks\":[]}");
        visibleOnlyVerify.succeed("""
                {"verified":true,"checks":[{"type":"textContains","passed":true}]}
                """, NOW);
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(open, visibleOnlyVerify));

        assertThat(service().codingEvidence("run-a", ACTOR).browserApiVerified()).isFalse();
    }

    @Test
    void treatsSuccessfulManagedLoopbackReadinessAsHttpVerificationEvidence() {
        NodeToolInvocationEntity waitHttp = successful("process.wait_http", "{\"processId\":\"proc-a\",\"url\":\"http://127.0.0.1:8080/health\"}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(waitHttp));

        CodingRunEvidenceView evidence = service().codingEvidence("run-a", ACTOR);

        assertThat(evidence.verificationTools()).containsExactly("process.wait_http");
        assertThat(evidence.commandVerifications()).containsExactly("http");
    }

    @Test
    void requiresDesktopUiVerificationToFollowTheFinalControlAction() {
        NodeToolInvocationEntity click = successful("system.desktop.ui.click", "{\"processId\":12,\"automationId\":\"Save\"}");
        NodeToolInvocationEntity verify = successful("system.desktop.ui.verify", "{\"processId\":12,\"automationId\":\"Save\"}");
        NodeToolInvocationEntity type = successful("system.desktop.ui.type", "{\"processId\":12,\"automationId\":\"Name\",\"text\":\"new\"}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(click, verify, type));

        CodingRunEvidenceView staleVerification = service().codingEvidence("run-a", ACTOR);

        assertThat(staleVerification.desktopUiVerified()).isFalse();

        NodeToolInvocationEntity finalVerify = successful("system.desktop.ui.verify", "{\"processId\":12,\"automationId\":\"Name\"}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(click, verify, type, finalVerify));

        assertThat(service().codingEvidence("run-a", ACTOR).desktopUiVerified()).isTrue();
    }

    @Test
    void treatsApprovedDesktopValueReadAsPostActionControlEvidence() {
        NodeToolInvocationEntity typed = successful("system.desktop.ui.type", "{\"processId\":12,\"automationId\":\"Name\",\"text\":\"new\"}");
        NodeToolInvocationEntity read = successful("system.desktop.ui.read_value", "{\"processId\":12,\"automationId\":\"Name\"}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(typed, read));

        assertThat(service().codingEvidence("run-a", ACTOR).desktopUiVerified()).isTrue();
    }

    @Test
    void ignoresGitReviewAndReadsThatHappenedBeforeTheLastWrite() {
        NodeToolInvocationEntity oldReview = successful("git.review", "{}");
        NodeToolInvocationEntity oldRead = successful("fs.read", "{\"path\":\"src/App.java\"}");
        NodeToolInvocationEntity write = successful("fs.write", "{\"path\":\"src/App.java\"}");
        NodeToolInvocationEntity command = successful("shell.run", "{\"command\":\"./gradlew test\"}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(oldReview, oldRead, write, command));

        CodingRunEvidenceView evidence = service().codingEvidence("run-a", ACTOR);

        assertThat(evidence.changedFiles()).containsExactly("src/App.java");
        assertThat(evidence.gitReviewed()).isFalse();
        assertThat(evidence.reviewedChangedFiles()).isEmpty();
    }

    @Test
    void treatsAPostChangeScopedGitDiffAsReviewEvidenceForFilesUnderThatScope() {
        NodeToolInvocationEntity write = successful("fs.write", "{\"path\":\"src/api/TaskService.java\"}");
        NodeToolInvocationEntity review = successful("git.review", "{}");
        NodeToolInvocationEntity diff = successful("git.diff", "{\"path\":\"src\",\"staged\":true}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(write, review, diff));

        CodingRunEvidenceView evidence = service().codingEvidence("run-a", ACTOR);

        assertThat(evidence.gitReviewed()).isTrue();
        assertThat(evidence.reviewedChangedFiles()).containsExactly("src/api/TaskService.java");
    }

    @Test
    void ignoresMalformedAndMachineLocalPathsInHistoricalRecords() {
        // 这些记录理论上不该由受限节点产生，但摘要必须能安全处理旧数据和坏数据。
        NodeToolInvocationEntity localPath = successful("fs.write", "{\"path\":\"C:\\\\Users\\\\name\\\\secret.txt\"}");
        NodeToolInvocationEntity parentPath = successful("fs.write", "{\"path\":\"../outside.txt\"}");
        NodeToolInvocationEntity malformedJson = successful("fs.apply_patch", "not-json");
        NodeToolInvocationEntity goodPath = successful("fs.write", "{\"path\":\"src/./Main.java\"}");

        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(localPath, parentPath, malformedJson, goodPath));

        assertThat(service().codingEvidence("run-a", ACTOR).changedFiles()).containsExactly("src/Main.java");
    }

    @Test
    void exposesOnlyTheScopedDesktopNoOpCountNeededByTheDeliveryGate() {
        NodeToolInvocationEntity inspected = successful("system.desktop.organize.list", "{}");
        inspected.succeed("{\"sortableFiles\":0,\"path\":\".\"}", NOW);
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(inspected));

        CodingRunEvidenceView evidence = service().codingEvidence("run-a", ACTOR);

        assertThat(evidence.succeededTools()).containsExactly("system.desktop.organize.list");
        assertThat(evidence.desktopSortableFiles()).isZero();
    }

    @Test
    void scoresCompleteEvidenceAndExplainsMissingVerification() {
        NodeToolInvocationEntity write = successful("fs.write", "{\"path\":\"src/App.java\"}");
        NodeToolInvocationEntity command = successful("shell.run", "{\"command\":\"./gradlew test\"}");
        NodeToolInvocationEntity browser = successful("browser.open", "{\"url\":\"http://localhost:8080\"}");
        NodeToolInvocationEntity browserVerify = successful("browser.verify", "{\"checks\":[]}");
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(write, command, browser, browserVerify));

        CodingRunQualityView complete = service().codingQuality("run-a", ACTOR);
        assertThat(complete.score()).isEqualTo(100);
        assertThat(complete.grade()).isEqualTo("excellent");
        assertThat(complete.recommendations()).isEmpty();

        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(write));
        CodingRunQualityView incomplete = service().codingQuality("run-a", ACTOR);
        // 仅修改文件仍保留“没有工具失败”的 20 分，因此得分是 (25 + 20) / 90 = 50。
        assertThat(incomplete.score()).isEqualTo(50);
        assertThat(incomplete.grade()).isEqualTo("needs-verification");
        assertThat(incomplete.recommendations()).contains("至少成功执行一次构建、测试或其他命令验证。");
    }

    private NodeService service() {
        return new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
    }

    private NodeToolInvocationEntity successful(String toolName, String argumentsJson) {
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "id-" + toolName, ACTOR.tenantId(), "run-a", "call-" + toolName, "node-a", toolName, argumentsJson, NOW);
        invocation.start(NOW);
        invocation.succeed("{}", NOW);
        return invocation;
    }

    private NodeToolInvocationEntity failed(String toolName) {
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "id-" + toolName, ACTOR.tenantId(), "run-a", "call-" + toolName, "node-a", toolName, "{}", NOW);
        invocation.start(NOW);
        invocation.fail(NodeToolInvocationStatus.FAILED, "test failure", NOW);
        return invocation;
    }
}
