package io.github.yourname.agentstudio.orchestration;

public enum RunEventType {
    RUN_STARTED,
    STEP_STARTED,
    RETRIEVAL_COMPLETED,
    TOKEN_DELTA,
    STEP_COMPLETED,
    FINAL_ANSWER,
    RUN_FAILED,
    RUN_CANCELLED
}
