package io.github.yourname.agentstudio.conversation;

public class ConversationArchivedException extends RuntimeException {

    public ConversationArchivedException(String conversationId) {
        super("Conversation is archived: " + conversationId);
    }
}
