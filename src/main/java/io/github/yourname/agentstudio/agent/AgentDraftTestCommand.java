package io.github.yourname.agentstudio.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AgentDraftTestCommand(
        @NotEmpty @Size(max = 20) List<@Valid AgentDraftTestMessage> messages,
        @Size(max = 120) String modelProfileId) {
}
