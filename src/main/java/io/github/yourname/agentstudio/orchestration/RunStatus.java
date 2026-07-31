package io.github.yourname.agentstudio.orchestration;

public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
