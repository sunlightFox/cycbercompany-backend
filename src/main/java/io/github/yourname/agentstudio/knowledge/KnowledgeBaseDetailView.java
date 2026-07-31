package io.github.yourname.agentstudio.knowledge;

import java.util.List;

public record KnowledgeBaseDetailView(KnowledgeBaseView summary, List<KnowledgeDocumentView> documents) {
}
