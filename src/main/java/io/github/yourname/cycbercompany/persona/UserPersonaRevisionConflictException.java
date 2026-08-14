package io.github.yourname.cycbercompany.persona;

public class UserPersonaRevisionConflictException extends RuntimeException {
    private final String personaId;
    private final long expectedRevision;
    private final long actualRevision;

    public UserPersonaRevisionConflictException(String personaId, long expectedRevision, long actualRevision) {
        super("User persona was updated by another editor. Refresh it before saving again.");
        this.personaId = personaId;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String personaId() { return personaId; }
    public long expectedRevision() { return expectedRevision; }
    public long actualRevision() { return actualRevision; }
}
