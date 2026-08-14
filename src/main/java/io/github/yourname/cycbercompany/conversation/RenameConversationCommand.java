package io.github.yourname.cycbercompany.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameConversationCommand(@NotBlank @Size(max = 160) String title) {
}
