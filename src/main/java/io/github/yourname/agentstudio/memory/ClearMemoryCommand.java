package io.github.yourname.agentstudio.memory;

import jakarta.validation.constraints.Size;

public record ClearMemoryCommand(@Size(max = 200) String agentId) {
}
