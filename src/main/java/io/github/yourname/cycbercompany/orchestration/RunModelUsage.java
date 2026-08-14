package io.github.yourname.cycbercompany.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.model.ModelGateway;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.LinkedHashMap;
import java.util.Map;

record RunModelUsage(
        String phase,
        String modelProfileId,
        String rawModel,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {

    static void publish(
            RunEventPublisher events,
            ObjectMapper objectMapper,
            String runId,
            String phase,
            String modelProfileId,
            ModelGateway.ModelAnswer answer,
            long startedNanos,
            ActorContext actor) {
        if (answer == null) return;
        long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase == null || phase.isBlank() ? "model" : phase);
        payload.put("modelProfileId", modelProfileId);
        payload.put("rawModel", answer.rawModel());
        payload.put("promptTokens", answer.promptTokens());
        payload.put("completionTokens", answer.completionTokens());
        payload.put("latencyMs", latencyMs);
        try {
            events.publish(runId, RunEventType.MODEL_USAGE, objectMapper.writeValueAsString(payload), actor);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize model usage.", ex);
        }
    }
}
