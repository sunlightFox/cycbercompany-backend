package io.github.yourname.agentstudio.agent;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

/**
 * 持久化的 Agent 定义。
 *
 * <p>Agent 是“如何工作”的配置，而不是一次执行记录。它包含系统提示词、默认模型和工具
 * 白名单；创建 Run 时，编排层会读取这些字段并把它们复制到不可变的 RunSpec 中。
 */
@Entity(name = "agent_definition")
public class AgentDefinitionEntity {

    @Id
    /** Agent 的稳定 ID，既用于 API，也用于 Run 快照中的引用。 */
    private String id;
    /** 用户在界面看到的名称。 */
    private String name;
    /** 给用户看的简短说明，不参与模型推理。 */
    private String description;
    @Lob
    /** Agent 的系统提示词，定义角色、输出规则和安全边界。 */
    private String systemPrompt;
    /** 默认模型 Profile；Run 可以通过请求显式选择其他模型。 */
    private String defaultModelProfileId;
    /** 允许该 Agent 使用的工具规则，通常是逗号或空格分隔的名称。 */
    private String toolAllowList;
    /** 关闭后不能创建新的 Run，但历史 Run 仍然可以查询。 */
    private boolean enabled;
    /** 创建时间，用于展示和审计。 */
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

    /**
     * Updates safe, user-visible defaults for the built-in demo assistant.
     *
     * <p>The entity keeps most fields immutable from the outside so regular
     * application code does not accidentally rewrite agent definitions. The
     * local seed/migration code is the one place where changing defaults is
     * expected, because older local H2 databases keep the first-seeded values.
     */
    public void updateRuntimeDefaults(String systemPrompt, String toolAllowList) {
        this.systemPrompt = systemPrompt;
        this.toolAllowList = toolAllowList;
    }

    public void updateBasicInfo(String name, String description, String systemPrompt) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
    }

    /** A blank value intentionally delegates model selection to the global default. */
    public void updateDefaultModelProfileId(String defaultModelProfileId) {
        this.defaultModelProfileId = defaultModelProfileId == null || defaultModelProfileId.isBlank()
                ? null
                : defaultModelProfileId.trim();
    }

    public void updatePublishedSnapshot(
            String name,
            String description,
            String systemPrompt,
            String defaultModelProfileId,
            String toolAllowList) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.defaultModelProfileId = defaultModelProfileId;
        this.toolAllowList = toolAllowList;
        this.enabled = true;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
