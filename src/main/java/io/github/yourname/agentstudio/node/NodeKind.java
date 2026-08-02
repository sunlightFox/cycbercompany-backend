package io.github.yourname.agentstudio.node;

/** Whether a node was explicitly registered or provisioned for this local installation. */
public enum NodeKind {
    MANAGED_LOCAL,
    /** A user-registered computer. It is never picked automatically from a multi-device pool. */
    REGISTERED,
    /**
     * An administrator-designated, non-personal execution sandbox. Only this kind is eligible for
     * tag/capability based automatic routing; an explicit node selection is still required for
     * personal computers.
     */
    SANDBOX
}
