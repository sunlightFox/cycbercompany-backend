package io.github.yourname.agentstudio.orchestration;

public enum RunStatus {
    QUEUED,
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    NEEDS_VERIFICATION,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
