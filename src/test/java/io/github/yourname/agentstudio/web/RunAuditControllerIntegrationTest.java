package io.github.yourname.agentstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.yourname.agentstudio.orchestration.AgentRunEntity;
import io.github.yourname.agentstudio.orchestration.AgentRunRepository;
import io.github.yourname.agentstudio.orchestration.RunEventEntity;
import io.github.yourname.agentstudio.orchestration.RunEventRepository;
import io.github.yourname.agentstudio.orchestration.RunEventType;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:run-audit-controller;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
@AutoConfigureMockMvc
class RunAuditControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private RunEventRepository events;

    @Test
    void returnsPersistedAuditContract() throws Exception {
        Instant createdAt = Instant.parse("2026-08-08T08:00:00Z");
        ActorContext actor = ActorContext.local();
        runs.save(new AgentRunEntity(
                "run-audit-http", actor.tenantId(), actor.userId(), "conversation-1",
                "model-1", "agent-1", createdAt));
        events.save(new RunEventEntity(
                actor.tenantId(), "run-audit-http", 1, RunEventType.MODEL_USAGE,
                "{\"phase\":\"conversation\",\"modelProfileId\":\"model-1\",\"rawModel\":\"gpt-test\",\"promptTokens\":120,\"completionTokens\":30,\"latencyMs\":450}",
                createdAt.plusSeconds(1)));

        mvc.perform(get("/api/v1/runs/{id}/audit", "run-audit-http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value("run-audit-http"))
                .andExpect(jsonPath("$.run.agentId").value("agent-1"))
                .andExpect(jsonPath("$.snapshot").doesNotExist())
                .andExpect(jsonPath("$.summary.modelCalls").value(1))
                .andExpect(jsonPath("$.usage.promptTokens").value(120))
                .andExpect(jsonPath("$.usage.completionTokens").value(30))
                .andExpect(jsonPath("$.usage.totalTokens").value(150))
                .andExpect(jsonPath("$.usage.modelLatencyMs").value(450))
                .andExpect(jsonPath("$.timing.totalMs").isNumber())
                .andExpect(jsonPath("$.timeline[0].kind").value("model"))
                .andExpect(jsonPath("$.artifacts").isArray());
    }

    @Test
    void doesNotExposeAnotherTenantsRun() throws Exception {
        runs.save(new AgentRunEntity(
                "run-foreign", "another-tenant", "other-user", "conversation-2",
                "model-2", "agent-2", Instant.now()));

        mvc.perform(get("/api/v1/runs/{id}/audit", "run-foreign"))
                .andExpect(status().is4xxClientError());
    }
}
