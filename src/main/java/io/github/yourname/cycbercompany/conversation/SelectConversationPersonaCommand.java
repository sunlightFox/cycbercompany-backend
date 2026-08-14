package io.github.yourname.cycbercompany.conversation;

import jakarta.validation.constraints.Size;

public record SelectConversationPersonaCommand(@Size(max = 200) String personaId) {
}
