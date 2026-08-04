package io.github.yourname.agentstudio.model;

/** A retryable provider or transport failure before a model turn is completed. */
public class ModelTransientException extends ModelGatewayException {

    private final Integer statusCode;

    public ModelTransientException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
