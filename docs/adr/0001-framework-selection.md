# ADR-0001：选择 Spring AI 作为唯一 AI 框架

- 状态：Accepted
- 日期：2026-07-30

## 背景

项目要求使用 Java 和 Spring 生态，并覆盖模型切换、多模态、工具、知识库、Skill、MCP 和多 Agent。

候选方案包括：

- Spring AI；
- LangChain4j；
- 两者同时使用；
- 直接使用各模型厂商 SDK。

## 决策

使用 Spring AI 2.0 作为唯一 AI 基础框架。多 Agent 编排、运行状态、权限和事件模型由项目自行实现。

## 原因

- 与 Spring Boot 配置、生命周期、观测和依赖注入直接集成；
- 提供统一的 ChatModel、EmbeddingModel、VectorStore、Tool 和 Advisor 抽象；
- 官方支持多模态、RAG 和 MCP；
- 减少两套 AI 类型之间的适配和调试成本；
- 自行实现编排层，更能展示候选人的系统设计能力。

## 后果

正面：

- 架构和类型体系统一；
- 依赖更少；
- Spring 项目使用者更容易理解。

负面：

- 不直接使用 LangChain4j 已有的高层 Agentic API；
- 多 Agent 状态机和工作流需要自己实现；
- Spring AI 大版本升级时需要关注 API 变更。

## 约束

业务模块不能暴露模型厂商 SDK 类型。Provider 特有类型只能出现在 `model.internal`。

