package io.github.yourname.cycbercompany.agent;

public class AgentIdentityRevisionConflictException extends RuntimeException {

    private final String agentId;
    private final long expectedRevision;
    private final long actualRevision;

    public AgentIdentityRevisionConflictException(String agentId, long expectedRevision, long actualRevision) {
        super("Agent settings were updated by another editor. Refresh them before saving again.");
        this.agentId = agentId;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String agentId() { return agentId; }
    public long expectedRevision() { return expectedRevision; }
    public long actualRevision() { return actualRevision; }
}
