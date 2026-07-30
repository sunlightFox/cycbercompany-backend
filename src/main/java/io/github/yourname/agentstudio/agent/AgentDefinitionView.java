package io.github.yourname.agentstudio.agent;

public record AgentDefinitionView(
        String id,
        String name,
        String description,
        String systemPrompt,
        String defaultModelProfileId,
        String toolAllowList,
        boolean enabled) {

    public static AgentDefinitionView from(AgentDefinitionEntity entity) {
        return new AgentDefinitionView(
                entity.id(),
                entity.name(),
                entity.description(),
                entity.systemPrompt(),
                entity.defaultModelProfileId(),
                entity.toolAllowList(),
                entity.enabled());
    }
}
