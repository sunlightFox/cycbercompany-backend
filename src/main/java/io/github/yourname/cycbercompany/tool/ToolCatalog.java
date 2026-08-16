package io.github.yourname.cycbercompany.tool;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 声明内建工具的能力目录。
 *
 * <p>“能在目录中看见”与“某次运行可执行”是两回事：后者还会受到 Agent 白名单、租户、
 * 节点在线状态和风险审批策略约束。把发现和授权分开是工具系统的重要设计原则。
 */
@Service
public class ToolCatalog {

    public List<RegisteredTool> list() {
        // 内建工具是代码常量；MCP/Node 工具由控制器在查询时与此列表合并。
        return List.of(
                new RegisteredTool("local_time", "Return the server's current time.", RiskLevel.LOW, false),
                new RegisteredTool("knowledge_search", "Search tenant-scoped knowledge bases.", RiskLevel.LOW, false),
                new RegisteredTool("web_search", "Search the public web for current external evidence.", RiskLevel.LOW, false));
    }
}
