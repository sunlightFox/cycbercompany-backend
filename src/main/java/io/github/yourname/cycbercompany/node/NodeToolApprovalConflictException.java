package io.github.yourname.cycbercompany.node;

/** Raised when a human approval decision is submitted more than once. */
public class NodeToolApprovalConflictException extends IllegalStateException {

    public NodeToolApprovalConflictException(String message) {
        super(message);
    }
}
