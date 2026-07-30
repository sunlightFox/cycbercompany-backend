package io.github.yourname.agentstudio.orchestration;

public record CreateRunResponse(String runId, RunStatus status, String eventsUrl) {
}
