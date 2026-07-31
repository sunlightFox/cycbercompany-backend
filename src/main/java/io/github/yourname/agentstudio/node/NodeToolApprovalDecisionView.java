package io.github.yourname.agentstudio.node;

/** The durable approval decision together with the single resulting node execution, if approved. */
public record NodeToolApprovalDecisionView(NodeToolApprovalView approval, NodeToolCallResult execution) {
}
