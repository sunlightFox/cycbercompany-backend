package io.github.yourname.cycbercompany.memory;

import jakarta.validation.constraints.Size;

public record ClearMemoryCommand(
        @Size(max = 200) String agentId,
        @Size(max = 200) String personaId,
        boolean sharedOnly) {

    public ClearMemoryCommand(String agentId) {
        this(agentId, null, false);
    }

    public ClearMemoryCommand(String agentId, String personaId) {
        this(agentId, personaId, false);
    }
}
