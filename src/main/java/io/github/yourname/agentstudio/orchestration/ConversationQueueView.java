package io.github.yourname.agentstudio.orchestration;

import java.util.List;

/** A frontend-ready queue snapshot with concise copy for the chat composer. */
public record ConversationQueueView(
        String conversationId,
        String activeRunId,
        List<ConversationRunQueue.QueueEntry> pending,
        QueueGuideView guide) {

    static ConversationQueueView from(String conversationId, ConversationRunQueue.QueueSnapshot snapshot) {
        int waiting = snapshot.pending().size();
        String message = snapshot.activeRunId() == null
                ? "Send a message to begin. Messages in this conversation run one at a time."
                : waiting == 0
                        ? "This response is in progress. Your next message will be queued and run after it finishes."
                        : waiting + " message" + (waiting == 1 ? " is" : "s are")
                                + " waiting. You can keep writing; messages run in the order sent.";
        return new ConversationQueueView(
                conversationId,
                snapshot.activeRunId(),
                snapshot.pending(),
                new QueueGuideView(message, "Cancel a queued message to remove it without interrupting the response in progress."));
    }

    public record QueueGuideView(String message, String cancelHint) {
    }
}
