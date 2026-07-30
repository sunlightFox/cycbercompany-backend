package io.github.yourname.agentstudio.conversation;

import java.time.Instant;
import java.util.List;

public record ConversationView(String id, String title, Instant createdAt, List<MessageView> messages) {
}
