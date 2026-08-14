package io.github.yourname.cycbercompany.conversation;

public class ConversationArchivedException extends RuntimeException {

    public ConversationArchivedException(String conversationId) {
        super("Conversation is archived: " + conversationId);
    }
}
