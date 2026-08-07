package io.github.yourname.agentstudio.memory;

public class MemoryRevisionConflictException extends RuntimeException {

    private final String memoryId;
    private final long expectedRevision;
    private final long actualRevision;

    public MemoryRevisionConflictException(String memoryId, long expectedRevision, long actualRevision) {
        super("Memory was updated by another editor. Refresh it before saving again.");
        this.memoryId = memoryId;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String memoryId() { return memoryId; }
    public long expectedRevision() { return expectedRevision; }
    public long actualRevision() { return actualRevision; }
}
