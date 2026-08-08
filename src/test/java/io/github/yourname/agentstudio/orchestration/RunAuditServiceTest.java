package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.artifact.ArtifactService;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunAuditServiceTest {

    @Test
    void aggregatesProviderUsageAndKeepsTokenDeltasOutOfTimeline() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        RunEventRepository events = mock(RunEventRepository.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        RunQueryService runQueries = mock(RunQueryService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ActorContext actor = new ActorContext("tenant-a", "user-a", Set.of(), Set.of());
        AgentRunEntity run = new AgentRunEntity(
                "run-1", "tenant-a", "user-a", "conversation-1", "model-1", "agent-1", Instant.now());
        List<RunEventEntity> runEvents = List.of(
                event(1, RunEventType.RUN_STARTED, "single-agent"),
                event(2, RunEventType.MODEL_USAGE,
                        "{\"phase\":\"conversation\",\"modelProfileId\":\"model-1\",\"rawModel\":\"gpt-test\",\"promptTokens\":120,\"completionTokens\":30,\"latencyMs\":450}"),
                event(3, RunEventType.TOKEN_DELTA, "hidden from audit timeline"),
                event(4, RunEventType.MODEL_USAGE,
                        "{\"phase\":\"synthesis\",\"modelProfileId\":\"model-1\",\"rawModel\":\"gpt-test\",\"promptTokens\":80,\"completionTokens\":20,\"latencyMs\":250}"),
                event(5, RunEventType.FINAL_ANSWER, "answer"));
        when(runs.findByIdAndTenantId("run-1", "tenant-a")).thenReturn(java.util.Optional.of(run));
        when(events.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc("run-1", "tenant-a", 0))
                .thenReturn(runEvents);
        when(artifacts.listRunArtifacts("run-1", actor)).thenReturn(List.of());
        when(runQueries.get("run-1", actor)).thenReturn(RunView.from(run));

        RunAuditView audit = new RunAuditService(runs, events, artifacts, objectMapper, runQueries)
                .get("run-1", actor);

        assertThat(audit.usage().modelCalls()).isEqualTo(2);
        assertThat(audit.usage().providerReportedCalls()).isEqualTo(2);
        assertThat(audit.usage().promptTokens()).isEqualTo(200);
        assertThat(audit.usage().completionTokens()).isEqualTo(50);
        assertThat(audit.usage().totalTokens()).isEqualTo(250);
        assertThat(audit.usage().modelLatencyMs()).isEqualTo(700);
        assertThat(audit.timeline()).hasSize(4);
        assertThat(audit.timeline()).noneMatch(item -> item.title().contains("token delta"));
    }

    @Test
    void tracksCallsWithoutInventingProviderTokenUsage() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        RunEventRepository events = mock(RunEventRepository.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        RunQueryService runQueries = mock(RunQueryService.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", Set.of(), Set.of());
        AgentRunEntity run = new AgentRunEntity(
                "run-2", "tenant-a", "user-a", "conversation-1", "model-1", "agent-1", Instant.now());
        when(runs.findByIdAndTenantId("run-2", "tenant-a")).thenReturn(java.util.Optional.of(run));
        when(events.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc("run-2", "tenant-a", 0))
                .thenReturn(List.of(event(1, RunEventType.MODEL_USAGE,
                        "{\"phase\":\"conversation\",\"modelProfileId\":\"model-1\",\"rawModel\":\"local\",\"promptTokens\":null,\"completionTokens\":null,\"latencyMs\":12}")));
        when(artifacts.listRunArtifacts("run-2", actor)).thenReturn(List.of());
        when(runQueries.get("run-2", actor)).thenReturn(RunView.from(run));

        RunAuditView audit = new RunAuditService(runs, events, artifacts, new ObjectMapper(), runQueries)
                .get("run-2", actor);

        assertThat(audit.usage().modelCalls()).isEqualTo(1);
        assertThat(audit.usage().providerReportedCalls()).isZero();
        assertThat(audit.usage().totalTokens()).isZero();
        assertThat(audit.usage().modelLatencyMs()).isEqualTo(12);
    }

    @Test
    void exposesStructuredCitationsFromRetrievalEvents() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        RunEventRepository events = mock(RunEventRepository.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        RunQueryService runQueries = mock(RunQueryService.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", Set.of(), Set.of());
        AgentRunEntity run = new AgentRunEntity(
                "run-citations", "tenant-a", "user-a", "conversation-1", "model-1", "agent-1", Instant.now());
        String citations = """
                [{"id":"knowledge-1","source":"Knowledge base","title":"Release guide","quote":"Deploy after verification.","location":"kb-1/doc-1#chunk=2","type":"knowledge"},
                {"id":"web-1","source":"Web","title":"Provider status","quote":"All systems operational.","location":"https://status.example.test","type":"web"}]
                """;
        when(runs.findByIdAndTenantId("run-citations", "tenant-a")).thenReturn(java.util.Optional.of(run));
        when(events.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc("run-citations", "tenant-a", 0))
                .thenReturn(List.of(
                        event(1, RunEventType.RETRIEVAL_SOURCES, citations),
                        event(2, RunEventType.RETRIEVAL_SOURCES, citations),
                        event(3, RunEventType.RETRIEVAL_SOURCES, "not-json")));
        when(artifacts.listRunArtifacts("run-citations", actor)).thenReturn(List.of());
        when(runQueries.get("run-citations", actor)).thenReturn(RunView.from(run));

        RunAuditView audit = new RunAuditService(runs, events, artifacts, new ObjectMapper(), runQueries)
                .get("run-citations", actor);

        assertThat(audit.citations()).extracting(RunAuditView.RunAuditCitation::id)
                .containsExactly("knowledge-1", "web-1");
        assertThat(audit.citations().getFirst().quote()).isEqualTo("Deploy after verification.");
    }

    @Test
    void exposesPersonaAndMemorySummaryWithoutExposingMemoryContent() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        RunEventRepository events = mock(RunEventRepository.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        RunQueryService runQueries = mock(RunQueryService.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", Set.of(), Set.of());
        AgentRunEntity run = new AgentRunEntity(
                "run-3", "tenant-a", "user-a", "conversation-1", "model-1", "agent-1", Instant.now());
        run.bindRunSpec("""
                {"agentId":"agent-1","modelProfileId":"model-1","requestedToolNames":[],
                "memorySnapshots":[
                  {"id":"memory-1","type":"PROFILE","content":"Do not expose this preference","confidence":0.9,"importance":0.8},
                  {"id":"memory-2","type":"SEMANTIC","content":"Do not expose this fact","confidence":0.8,"importance":0.7}
                ],"userPersonaId":"persona-1","userPersonaSnapshotJson":"{\\"id\\":\\"persona-1\\",\\"name\\":\\"Product lead\\"}"}
                """, "digest");
        when(runs.findByIdAndTenantId("run-3", "tenant-a")).thenReturn(java.util.Optional.of(run));
        when(events.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc("run-3", "tenant-a", 0))
                .thenReturn(List.of());
        when(artifacts.listRunArtifacts("run-3", actor)).thenReturn(List.of());
        when(runQueries.get("run-3", actor)).thenReturn(RunView.from(run));

        RunAuditView audit = new RunAuditService(runs, events, artifacts, new ObjectMapper(), runQueries)
                .get("run-3", actor);

        assertThat(audit.snapshot().personaId()).isEqualTo("persona-1");
        assertThat(audit.snapshot().personaName()).isEqualTo("Product lead");
        assertThat(audit.snapshot().recalledMemoryCount()).isEqualTo(2);
        assertThat(audit.snapshot().recalledMemoryTypes()).containsExactly("PROFILE", "SEMANTIC");
        assertThat(audit.snapshot().toString()).doesNotContain("Do not expose this");
    }

    private static RunEventEntity event(long sequence, RunEventType type, String payload) {
        return new RunEventEntity("tenant-a", "run", sequence, type, payload, Instant.now());
    }
}
