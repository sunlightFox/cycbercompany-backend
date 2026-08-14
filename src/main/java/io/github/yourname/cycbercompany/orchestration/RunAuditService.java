package io.github.yourname.cycbercompany.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.yourname.cycbercompany.artifact.ArtifactService;
import io.github.yourname.cycbercompany.artifact.ArtifactView;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunAuditService {

    private final AgentRunRepository runs;
    private final RunEventRepository events;
    private final ArtifactService artifacts;
    private final ObjectMapper objectMapper;
    private final RunQueryService runQueries;

    public RunAuditService(
            AgentRunRepository runs,
            RunEventRepository events,
            ArtifactService artifacts,
            ObjectMapper objectMapper,
            RunQueryService runQueries) {
        this.runs = runs;
        this.events = events;
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
        this.runQueries = runQueries;
    }

    @Transactional(readOnly = true)
    public RunAuditView get(String runId, ActorContext actor) {
        AgentRunEntity run = runs.findByIdAndTenantId(runId, actor.tenantId())
                .filter(value -> value.userId() == null || actor.userId().equals(value.userId()))
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        List<RunEventEntity> runEvents = events
                .findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(runId, actor.tenantId(), 0);
        List<ArtifactView> runArtifacts = artifacts.listRunArtifacts(runId, actor);
        List<RunModelUsage> usages = runEvents.stream()
                .filter(event -> event.type() == RunEventType.MODEL_USAGE)
                .map(this::parseUsage)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new RunAuditView(
                runQueries.get(runId, actor),
                snapshot(run),
                summary(runEvents, runArtifacts, usages),
                usage(usages),
                timing(run),
                timeline(runEvents),
                citations(runEvents),
                runArtifacts);
    }

    private RunAuditView.RunAuditSnapshot snapshot(AgentRunEntity run) {
        if (run.runSpecJson() == null || run.runSpecJson().isBlank()) return null;
        try {
            RunSpec spec = objectMapper.readValue(run.runSpecJson(), RunSpec.class);
            return new RunAuditView.RunAuditSnapshot(
                    spec.agentId(),
                    blankToNull(spec.agentVersionId()),
                    spec.modelProfileId(),
                    spec.toolBindings().stream().map(binding -> binding.logicalName()).distinct().toList(),
                    spec.knowledgeBaseIds(),
                    spec.mcpConnectionIds(),
                    blankToNull(spec.nodeId()),
                    spec.workingDirectory() == null ? "" : spec.workingDirectory(),
                    spec.skillBindings().stream().map(binding -> binding.skillId()).distinct().toList(),
                    blankToNull(spec.userPersonaId()),
                    personaName(spec.userPersonaSnapshotJson()),
                    spec.memorySnapshots().size(),
                    spec.memorySnapshots().stream()
                            .filter(memory -> memory.type() != null)
                            .map(memory -> memory.type().name())
                            .distinct()
                            .toList(),
                    run.createdAt());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String personaName(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            JsonNode snapshot = objectMapper.readTree(snapshotJson);
            return snapshot == null ? null : blankToNull(snapshot.path("name").asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static RunAuditView.RunAuditSummary summary(
            List<RunEventEntity> events,
            List<ArtifactView> artifacts,
            List<RunModelUsage> usages) {
        int tools = (int) events.stream().filter(event -> event.type() == RunEventType.TOOL_CALL_STARTED).count();
        int approvals = (int) events.stream().filter(event -> event.type() == RunEventType.TOOL_APPROVAL_REQUIRED).count();
        return new RunAuditView.RunAuditSummary(events.size(), usages.size(), tools, approvals, artifacts.size());
    }

    private static RunAuditView.RunUsageSummary usage(List<RunModelUsage> usages) {
        int reported = 0;
        long prompt = 0;
        long completion = 0;
        long latency = 0;
        for (RunModelUsage usage : usages) {
            if (usage.promptTokens() != null || usage.completionTokens() != null) reported++;
            prompt += positive(usage.promptTokens());
            completion += positive(usage.completionTokens());
            latency += Math.max(0L, usage.latencyMs());
        }
        return new RunAuditView.RunUsageSummary(
                usages.size(), reported, prompt, completion, prompt + completion, latency);
    }

    private static RunAuditView.RunTimingSummary timing(AgentRunEntity run) {
        Instant now = Instant.now();
        Instant started = run.startedAt();
        Instant finished = run.finishedAt();
        long queueMs = started == null ? duration(run.createdAt(), finished == null ? now : finished) : duration(run.createdAt(), started);
        long executionMs = started == null ? 0L : duration(started, finished == null ? now : finished);
        return new RunAuditView.RunTimingSummary(queueMs, executionMs, duration(run.createdAt(), finished == null ? now : finished));
    }

    private List<RunAuditView.RunAuditTimelineEntry> timeline(List<RunEventEntity> runEvents) {
        List<RunAuditView.RunAuditTimelineEntry> result = new ArrayList<>();
        for (RunEventEntity event : runEvents) {
            if (event.type() == RunEventType.TOKEN_DELTA) continue;
            RunModelUsage modelUsage = event.type() == RunEventType.MODEL_USAGE ? parseUsage(event) : null;
            result.add(new RunAuditView.RunAuditTimelineEntry(
                    "event-" + event.sequence(),
                    kind(event.type()),
                    title(event.type(), modelUsage),
                    detail(event, modelUsage),
                    status(event.type()),
                    event.createdAt(),
                    event.sequence()));
        }
        return List.copyOf(result);
    }

    private List<RunAuditView.RunAuditCitation> citations(List<RunEventEntity> runEvents) {
        java.util.Map<String, RunAuditView.RunAuditCitation> values = new java.util.LinkedHashMap<>();
        for (RunEventEntity event : runEvents) {
            if (event.type() != RunEventType.RETRIEVAL_SOURCES || event.payload() == null || event.payload().isBlank()) {
                continue;
            }
            try {
                List<RunAuditView.RunAuditCitation> parsed = objectMapper.readValue(
                        event.payload(), new TypeReference<List<RunAuditView.RunAuditCitation>>() { });
                for (RunAuditView.RunAuditCitation citation : parsed) {
                    if (citation != null && citation.id() != null && !citation.id().isBlank()) {
                        values.putIfAbsent(citation.id(), citation);
                    }
                }
            } catch (Exception ignored) {
                // Older or malformed retrieval events remain visible in the timeline.
            }
        }
        return List.copyOf(values.values());
    }

    private RunModelUsage parseUsage(RunEventEntity event) {
        try {
            return objectMapper.readValue(event.payload(), RunModelUsage.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String kind(RunEventType type) {
        if (type == RunEventType.MODEL_USAGE || type == RunEventType.MODEL_RATE_LIMITED
                || type == RunEventType.MODEL_PROVIDER_RETRYING) return "model";
        if (type.name().startsWith("TOOL_")) return type == RunEventType.TOOL_APPROVAL_REQUIRED ? "approval" : "tool";
        if (type.name().startsWith("RUN_")) return "run";
        return "event";
    }

    private static String title(RunEventType type, RunModelUsage usage) {
        return switch (type) {
            case RUN_QUEUED -> "Run queued";
            case RUN_STARTED -> "Run started";
            case MODEL_USAGE -> usage == null ? "Model call completed" : "Model call: " + usage.phase();
            case MODEL_RATE_LIMITED -> "Model rate limited";
            case MODEL_PROVIDER_RETRYING -> "Model provider retrying";
            case TOOL_CALL_REQUESTED -> "Tool requested";
            case TOOL_CALL_STARTED -> "Tool started";
            case TOOL_CALL_COMPLETED -> "Tool completed";
            case TOOL_CALL_FAILED -> "Tool failed";
            case TOOL_APPROVAL_REQUIRED -> "Approval required";
            case RUN_WAITING_APPROVAL -> "Waiting for approval";
            case RUN_RESUMED -> "Run resumed";
            case FINAL_ANSWER -> "Final answer delivered";
            case RUN_NEEDS_VERIFICATION -> "Verification required";
            case RUN_FAILED -> "Run failed";
            case RUN_CANCELLED -> "Run cancelled";
            default -> type.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String detail(RunEventEntity event, RunModelUsage usage) {
        if (usage != null) {
            long total = positive(usage.promptTokens()) + positive(usage.completionTokens());
            String tokens = usage.promptTokens() == null && usage.completionTokens() == null
                    ? "tokens unavailable"
                    : total + " tokens";
            return tokens + " · " + usage.latencyMs() + " ms · "
                    + (usage.rawModel() == null ? usage.modelProfileId() : usage.rawModel());
        }
        if (event.type() == RunEventType.FINAL_ANSWER || event.type() == RunEventType.TOKEN_DELTA) return null;
        String value = event.payload() == null ? "" : event.payload().strip();
        return value.isBlank() ? null : value.substring(0, Math.min(value.length(), 300));
    }

    private static String status(RunEventType type) {
        if (type == RunEventType.RUN_FAILED || type == RunEventType.TOOL_CALL_FAILED) return "failed";
        if (type == RunEventType.MODEL_RATE_LIMITED || type == RunEventType.MODEL_PROVIDER_RETRYING
                || type == RunEventType.RUN_NEEDS_VERIFICATION) return "warning";
        if (type == RunEventType.RUN_WAITING_APPROVAL || type == RunEventType.TOOL_APPROVAL_REQUIRED) return "waiting";
        return "complete";
    }

    private static long duration(Instant start, Instant end) {
        if (start == null || end == null || end.isBefore(start)) return 0L;
        return Duration.between(start, end).toMillis();
    }

    private static long positive(Integer value) {
        return value == null ? 0L : Math.max(0, value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
