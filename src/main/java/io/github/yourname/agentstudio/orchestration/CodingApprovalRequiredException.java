package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.model.ModelGateway;
import java.util.List;

/** Signals that a coding run has reached a tool call that requires a human decision. */
final class CodingApprovalRequiredException extends RuntimeException {

    private final String approvalId;
    private final String toolCallId;
    private final List<ModelGateway.ModelMessage> messages;

    CodingApprovalRequiredException(
            String approvalId,
            String toolCallId,
            List<ModelGateway.ModelMessage> messages) {
        super("Coding run is waiting for node tool approval: " + approvalId);
        this.approvalId = approvalId;
        this.toolCallId = toolCallId;
        this.messages = List.copyOf(messages);
    }

    String approvalId() { return approvalId; }
    String toolCallId() { return toolCallId; }
    List<ModelGateway.ModelMessage> messages() { return messages; }
}
