/**
 * MCP 边界适配模块。
 *
 * <p>MCP 连接、能力发现、工具调用审计都放在这里。它把外部 MCP Server 的工具转换成
 * 项目内部统一的 Tool 描述；业务层不要直接依赖 MCP 协议对象，否则模块边界会被协议污染。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"security", "tool", "knowledge"})
package io.github.yourname.agentstudio.mcp;
