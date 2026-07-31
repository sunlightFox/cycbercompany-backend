package io.github.yourname.agentstudio.orchestration;

public enum RunEventType {
    RUN_STARTED,
    STEP_STARTED,
    RETRIEVAL_COMPLETED,
    TOOL_CALL_REQUESTED,
    TOOL_CALL_STARTED,
    TOOL_CALL_COMPLETED,
    TOOL_CALL_FAILED,
    TOKEN_DELTA,
    STEP_COMPLETED,
    FINAL_ANSWER,
    RUN_FAILED,
    RUN_CANCELLED
}
