package io.github.yourname.cycbercompany.knowledge;

import java.util.List;

public record KnowledgeBaseDetailView(KnowledgeBaseView summary, List<KnowledgeDocumentView> documents) {
}
