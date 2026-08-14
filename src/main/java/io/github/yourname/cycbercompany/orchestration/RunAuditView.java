package io.github.yourname.cycbercompany.orchestration;

import io.github.yourname.cycbercompany.artifact.ArtifactView;
import java.time.Instant;
import java.util.List;

public record RunAuditView(
        RunView run,
        RunAuditSnapshot snapshot,
        RunAuditSummary summary,
        RunUsageSummary usage,
        RunTimingSummary timing,
        List<RunAuditTimelineEntry> timeline,
        List<RunAuditCitation> citations,
        List<ArtifactView> artifacts) {

    public record RunAuditSnapshot(
            String agentId,
            String agentVersionId,
            String modelProfileId,
            List<String> allowedTools,
            List<String> knowledgeBaseIds,
            List<String> mcpConnectionIds,
            String nodeId,
            String workingDirectory,
            List<String> skillIds,
            String personaId,
            String personaName,
            int recalledMemoryCount,
            List<String> recalledMemoryTypes,
            Instant createdAt) {
    }

    public record RunAuditSummary(
            int events,
            int modelCalls,
            int tools,
            int approvals,
            int artifacts) {
    }

    public record RunUsageSummary(
            int modelCalls,
            int providerReportedCalls,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long modelLatencyMs) {
    }

    public record RunTimingSummary(long queueMs, long executionMs, long totalMs) {
    }

    public record RunAuditTimelineEntry(
            String id,
            String kind,
            String title,
            String detail,
            String status,
            Instant occurredAt,
            long sequence) {
    }

    public record RunAuditCitation(
            String id,
            String source,
            String title,
            String quote,
            String location,
            String type) {
    }
}
