package io.github.yourname.cycbercompany.agent;

/**
 * 返回给前端的 Agent 视图。
 *
 * <p>View 只暴露允许公开的配置，不直接暴露 JPA 的生命周期或内部方法。
 */
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
