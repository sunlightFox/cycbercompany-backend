/**
 * Agent 定义模块。
 *
 * <p>这里保存“一个 Agent 是什么”：名称、系统提示词、默认模型、可用工具白名单等配置。
 * 新手可以把它理解成运行时的“角色说明书”。真正执行一次用户请求不在本模块完成，
 * 而是由 {@code orchestration} 模块读取这些定义后创建 Run。
 */
@org.springframework.modulith.ApplicationModule
package io.github.yourname.agentstudio.agent;
