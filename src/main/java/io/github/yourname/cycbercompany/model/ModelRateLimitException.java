package io.github.yourname.cycbercompany.model;

import java.time.Duration;

/** A provider-side 429 response, optionally carrying a Retry-After delay. */
public class ModelRateLimitException extends ModelGatewayException {

    private final Duration retryAfter;

    public ModelRateLimitException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
