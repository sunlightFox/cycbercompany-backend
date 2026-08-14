package io.github.yourname.cycbercompany.orchestration;

public record CreateRunResponse(String runId, RunStatus status, int queuePosition, String eventsUrl) {
}
