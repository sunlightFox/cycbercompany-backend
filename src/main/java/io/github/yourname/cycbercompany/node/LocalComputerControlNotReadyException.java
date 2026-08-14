package io.github.yourname.cycbercompany.node;

/** Signals that an automatic local task can be retried after the Companion reconnects. */
public final class LocalComputerControlNotReadyException extends IllegalArgumentException {

    public LocalComputerControlNotReadyException() {
        super("Local computer control is not ready. Start the local executor, then retry.");
    }
}
