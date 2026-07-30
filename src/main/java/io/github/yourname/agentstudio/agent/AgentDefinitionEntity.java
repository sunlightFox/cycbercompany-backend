package io.github.yourname.agentstudio.agent;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

@Entity(name = "agent_definition")
public class AgentDefinitionEntity {

    @Id
    private String id;
    private String name;
    private String description;
    @Lob
    private String systemPrompt;
    private String defaultModelProfileId;
    private String toolAllowList;
    private boolean enabled;
    private Instant createdAt;

    protected AgentDefinitionEntity() {
    }

    public AgentDefinitionEntity(
            String id,
            String name,
            String description,
            String systemPrompt,
            String defaultModelProfileId,
            String toolAllowList,
            boolean enabled,
            Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.defaultModelProfileId = defaultModelProfileId;
        this.toolAllowList = toolAllowList;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String systemPrompt() { return systemPrompt; }
    public String defaultModelProfileId() { return defaultModelProfileId; }
    public String toolAllowList() { return toolAllowList; }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }
}
