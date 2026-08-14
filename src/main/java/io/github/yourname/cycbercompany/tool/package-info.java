/**
 * 统一工具路由模块。
 *
 * <p>后端工具、MCP 工具、节点工具都会先被转换成统一的 ToolDescriptor，再由 ToolRouter
 * 计算 Agent 白名单与本次 Run 选择的交集。模型只看到稳定的工具名和 schema，
 * 看不到底层 Provider 的任意执行地址。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"security", "knowledge", "artifact"})
package io.github.yourname.cycbercompany.tool;
