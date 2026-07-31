package io.github.yourname.agentstudio.node;

public enum NodeToolInvocationStatus {
    REQUESTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    APPROVAL_REQUIRED
}
