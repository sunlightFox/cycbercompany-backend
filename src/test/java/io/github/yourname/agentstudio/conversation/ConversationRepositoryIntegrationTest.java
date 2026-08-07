package io.github.yourname.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conversation-repository-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
class ConversationRepositoryIntegrationTest {

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private MessageRepository messages;

    @Test
    void searchesConversationTitlesAndLobMessageContent() {
        var conversation = conversations.save(
                new ConversationEntity("searchable", "tenant-search", "Weekly planning", Instant.now()));
        messages.save(new MessageEntity(
                "tenant-search", conversation.id(), MessageRole.USER, "Review the release checklist", null, Instant.now()));

        var titleMatches = conversations.searchHistory(
                "tenant-search", "weekly", true, PageRequest.of(0, 20));
        var messageMatches = conversations.searchHistory(
                "tenant-search", "RELEASE", true, PageRequest.of(0, 20));

        assertThat(titleMatches).extracting(ConversationEntity::id).containsExactly("searchable");
        assertThat(messageMatches).extracting(ConversationEntity::id).containsExactly("searchable");
    }
}
