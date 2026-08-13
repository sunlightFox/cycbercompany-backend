package io.github.yourname.agentstudio.orchestration;

/**
 * Immutable, auditable result of the pre-execution routing decision.
 *
 * <p>The decision only selects from capabilities that the server has already made available. It
 * never grants file-system, shell, or node permissions.
 */
public record ExecutionIntentDecision(
        ExecutionIntent intent,
        double confidence,
        String source,
        String reason) {

    public ExecutionIntentDecision {
        intent = intent == null ? ExecutionIntent.CLARIFY : intent;
        confidence = Math.max(0.0d, Math.min(1.0d, confidence));
        source = source == null || source.isBlank() ? "unknown" : source;
        reason = reason == null ? "" : reason.strip();
    }
}
