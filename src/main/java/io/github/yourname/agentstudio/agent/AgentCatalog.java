package io.github.yourname.agentstudio.agent;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentCatalog {

    private final AgentDefinitionRepository repository;

    public AgentCatalog(AgentDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AgentDefinitionView> list() {
        return repository.findAll().stream().map(AgentDefinitionView::from).toList();
    }

    @Transactional(readOnly = true)
    public AgentDefinitionView get(String id) {
        return repository.findById(id).map(AgentDefinitionView::from)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));
    }
}
