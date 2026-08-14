package io.github.yourname.cycbercompany.orchestration;

/** Raised when an action request has no sufficiently reliable execution target. */
public final class ExecutionIntentClarificationException extends RuntimeException {

    private final ExecutionIntentDecision decision;

    public ExecutionIntentClarificationException(ExecutionIntentDecision decision) {
        super("Please confirm whether this request should run on this computer or be answered as a chat request.");
        this.decision = decision;
    }

    public ExecutionIntentDecision decision() {
        return decision;
    }
}
