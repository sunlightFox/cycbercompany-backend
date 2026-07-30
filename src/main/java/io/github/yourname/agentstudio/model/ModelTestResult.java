package io.github.yourname.agentstudio.model;

/**
 * Result of a lightweight model connectivity check.
 */
public record ModelTestResult(
        String modelProfileId,
        boolean success,
        String message,
        String responsePreview,
        Integer promptTokens,
        Integer completionTokens,
        String rawModel) {
}
