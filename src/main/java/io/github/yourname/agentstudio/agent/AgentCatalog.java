package io.github.yourname.agentstudio.agent;

import java.util.List;
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
}
