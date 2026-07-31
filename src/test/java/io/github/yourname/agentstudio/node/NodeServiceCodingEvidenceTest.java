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
        NodeToolInvocationEntity failedRead = failed("fs.read");

        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(write, patch, command, browser, failedRead));

        CodingRunEvidenceView evidence = service().codingEvidence("run-a", ACTOR);

        assertThat(evidence.runId()).isEqualTo("run-a");
        assertThat(evidence.toolCalls()).isEqualTo(5);
        assertThat(evidence.changedFiles()).containsExactly("projects/app/src/App.java");
        assertThat(evidence.verificationTools()).containsExactly("shell.run", "browser.open");
        assertThat(evidence.browserVerified()).isTrue();
        assertThat(evidence.failedTools()).containsExactly("fs.read");
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
