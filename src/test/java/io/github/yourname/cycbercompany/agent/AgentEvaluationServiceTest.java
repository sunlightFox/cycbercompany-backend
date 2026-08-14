package io.github.yourname.cycbercompany.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentEvaluationServiceTest {

    @Test
    void evaluatesConfiguredSuiteAndPersistsDigestBoundReport() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentManifestCompiler compiler = new AgentManifestCompiler(mapper);
        var manifest = AgentManifestTestData.valid(mapper);
        manifest.putObject("evaluation")
                .putArray("suiteIds").add("role-boundary-smoke");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("evaluation"))
                .put("requiredBeforePublish", true)
                .put("minimumPassRate", 1.0);
        var compiled = compiler.compile(manifest);
        var identity = new AgentIdentityEntity(
                "agent-1", "tenant-1", "owner-1", "Agent", "", "", "", "[]", "PRIVATE", Instant.now());
        var version = new AgentVersionEntity(
                "version-1", "agent-1", "tenant-1", 1, compiled, "owner-1", Instant.now());
        AgentIdentityRepository identities = mock(AgentIdentityRepository.class);
        AgentVersionRepository versions = mock(AgentVersionRepository.class);
        AgentEvaluationRepository evaluations = mock(AgentEvaluationRepository.class);
        AgentDraftTestService draftTests = mock(AgentDraftTestService.class);
        when(identities.findByIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(identity));
        when(versions.findByIdAndAgentIdAndTenantId("version-1", "agent-1", "tenant-1"))
                .thenReturn(Optional.of(version));
        when(draftTests.test(any(), any(), any(), any(), any())).thenReturn(new AgentDraftTestView(
                "agent-1", "version-1", compiled.manifestDigest(), "model-review",
                "I am a reviewer and I do not modify code in review-only requests.",
                10, 12, "model", "stop", false, List.of()));
        when(evaluations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentEvaluationService service = new AgentEvaluationService(
                identities,
                versions,
                evaluations,
                new AgentEvaluationSuiteRegistry(),
                draftTests,
                mapper);

        AgentEvaluationReportView report = service.evaluate(
                "agent-1", "version-1", "tenant-1", "owner-1");

        assertThat(report.passed()).isTrue();
        assertThat(report.score()).isEqualTo(1.0);
        assertThat(report.suites()).singleElement().satisfies(suite -> {
            assertThat(suite.suiteId()).isEqualTo("role-boundary-smoke");
            assertThat(suite.cases()).singleElement().extracting(AgentEvaluationReportView.CaseResult::passed)
                    .isEqualTo(true);
        });
        ArgumentCaptor<AgentEvaluationEntity> saved = ArgumentCaptor.forClass(AgentEvaluationEntity.class);
        verify(evaluations).save(saved.capture());
        assertThat(saved.getValue().manifestDigest()).isEqualTo(compiled.manifestDigest());
        assertThat(saved.getValue().versionId()).isEqualTo("version-1");
    }
}
