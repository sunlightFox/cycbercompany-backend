package io.github.yourname.agentstudio.node;

public enum NodeToolInvocationStatus {
    REQUESTED,
    DISPATCHED,
    ACCEPTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    UNKNOWN,
    CANCELLED,
    APPROVAL_REQUIRED
}
