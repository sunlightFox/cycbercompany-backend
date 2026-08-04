package io.github.yourname.agentstudio.orchestration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 编码工作流步骤的持久化状态。 */
public enum CodingWorkflowStepStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    BLOCKED("blocked"),
    NOT_REQUIRED("not_required");

    private final String wireValue;

    CodingWorkflowStepStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static CodingWorkflowStepStatus fromWireValue(String value) {
        for (CodingWorkflowStepStatus status : values()) {
            if (status.wireValue.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown coding workflow step status: " + value);
    }
}
