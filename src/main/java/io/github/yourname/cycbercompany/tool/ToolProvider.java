package io.github.yourname.cycbercompany.tool;

import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.List;

/**
 * 统一工具执行 SPI。
 *
 * <p>后端、MCP、Node 都实现这一接口。编排层只依赖 ToolRouter，不直接访问 WebSocket、
 * MCP 进程或具体工具实现，从源码依赖上阻止入口绕过统一策略和审计。
 */
public interface ToolProvider {

    String providerId();

    List<ToolDescriptor> discover(ToolDiscoveryRequest request);

    ToolProviderResult invoke(ToolInvocationRequest request);

    default List<ToolCleanupResult> cleanup(String runId, ActorContext actor) {
        return List.of();
    }
}
