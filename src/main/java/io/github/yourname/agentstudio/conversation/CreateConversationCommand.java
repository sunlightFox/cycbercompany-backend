package io.github.yourname.agentstudio.conversation;

public record CreateConversationCommand(String title, String personaId) {

    public CreateConversationCommand(String title) {
        this(title, null);
    }
}
