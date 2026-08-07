package io.github.yourname.agentstudio.nodeclient;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.FileSystemException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Bounded retry policy for the local companion's first control-plane bootstrap. */
final class BootstrapRetryPolicy {

    static final int MAX_ATTEMPTS = 8;

    private BootstrapRetryPolicy() {
    }

    static void execute(
            BootstrapAction action,
            BooleanSupplier stopping,
            IntConsumer retryScheduled,
            Sleeper sleeper) throws Exception {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                action.run();
                return;
            } catch (Exception ex) {
                if (stopping.getAsBoolean() || attempt == MAX_ATTEMPTS || !isTransientFailure(ex)) {
                    throw ex;
                }
                retryScheduled.accept(attempt + 1);
                sleeper.sleep(delayMillis(attempt));
            }
        }
    }

    static int delayMillis(int attempt) {
        int safeAttempt = Math.max(1, Math.min(attempt, MAX_ATTEMPTS));
        return Math.min(5_000, 500 * (1 << Math.min(safeAttempt - 1, 4)));
    }

    static boolean isTransientFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (current instanceof FileSystemException) {
                return false;
            }
            if (current instanceof IOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                // Older servers returned this mode mismatch as a 500. It is a
                // deterministic configuration error, not a control-plane outage.
                if (normalized.contains("configured for registered nodes only")) {
                    return false;
                }
                if (normalized.contains("connection refused")
                        || normalized.contains("connection reset")
                        || normalized.contains("connect timed out")
                        || normalized.matches(".*http [5][0-9][0-9].*")) {
                    return true;
                }
            }
        }
        return false;
    }

    @FunctionalInterface
    interface BootstrapAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(int millis) throws InterruptedException;
    }
}
