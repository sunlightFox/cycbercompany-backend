package io.github.yourname.cycbercompany.agent;

public class AgentRevisionConflictException extends RuntimeException {

    private final String versionId;
    private final long expectedRevision;
    private final long actualRevision;

    public AgentRevisionConflictException(String versionId, long expectedRevision, long actualRevision) {
        super("Agent draft was updated by another editor. Refresh it before saving again.");
        this.versionId = versionId;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String versionId() {
        return versionId;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long actualRevision() {
        return actualRevision;
    }
}
