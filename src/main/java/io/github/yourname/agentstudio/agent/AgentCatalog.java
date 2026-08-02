package io.github.yourname.agentstudio.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 定义的只读查询入口。
 *
 * <p>这里返回的是 View 而不是 JPA Entity，避免把数据库结构、懒加载行为或可修改对象泄露到
 * Web 层。运行编排按 ID 获取 Agent 后，再读取其系统提示词和工具白名单。
 */
@Service
public class AgentCatalog {

    private static final String DEFAULT_AGENT_ID = "default-assistant";

    private final AgentDefinitionRepository repository;

    public AgentCatalog(AgentDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AgentDefinitionView> list() {
        // View 是 API 合同；Entity 可以随持久化需求变化而不直接破坏调用方。
        return repository.findAll().stream().map(AgentDefinitionView::from).toList();
    }

    @Transactional(readOnly = true)
    public AgentDefinitionView get(String id) {
        return repository.findById(id).map(AgentDefinitionView::from)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));
    }

    @Transactional
    public AgentDefinitionView create(CreateAgentCommand command) {
        var defaultAgent = repository.findById(DEFAULT_AGENT_ID)
                .orElseThrow(() -> new IllegalArgumentException(
                        "A default agent is required before creating an employee."));
        var agent = new AgentDefinitionEntity(
                UUID.randomUUID().toString(),
                command.name().trim(),
                normalizeDescription(command.description()),
                command.systemPrompt().trim(),
                defaultAgent.defaultModelProfileId(),
                defaultAgent.toolAllowList(),
                true,
                Instant.now());
        return AgentDefinitionView.from(repository.save(agent));
    }

    @Transactional
    public AgentDefinitionView update(String id, UpdateAgentCommand command) {
        var agent = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));
        agent.updateBasicInfo(
                command.name().trim(),
                normalizeDescription(command.description()),
                command.systemPrompt().trim());
        return AgentDefinitionView.from(repository.save(agent));
    }

    private static String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }
}
