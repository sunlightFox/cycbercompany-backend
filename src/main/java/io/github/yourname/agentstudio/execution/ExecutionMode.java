package io.github.yourname.agentstudio.execution;

/** Defines which computers an installation may use for native tool execution. */
public enum ExecutionMode {
    PERSONAL_LOCAL,
    LOCAL_AND_NODES,
    NODES_ONLY;

    public boolean usesManagedLocalExecutor() {
        return this != NODES_ONLY;
    }

    public boolean exposesRegisteredNodes() {
        return this != PERSONAL_LOCAL;
    }
}
