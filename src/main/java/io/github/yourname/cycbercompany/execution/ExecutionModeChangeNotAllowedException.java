package io.github.yourname.cycbercompany.execution;

/** Raised when a shared backend is asked to enable an isolated-only topology. */
public class ExecutionModeChangeNotAllowedException extends RuntimeException {

    public ExecutionModeChangeNotAllowedException(String message) {
        super(message);
    }
}
