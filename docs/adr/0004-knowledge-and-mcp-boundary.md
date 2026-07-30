# ADR-0004：知识库作为单一业务模块，MCP 作为边界适配

- 状态：Accepted
- 日期：2026-07-30

## 背景

项目既需要内置企业知识库，也需要通过 MCP 接入和输出能力。若把知识摄取、检索和存储
拆成多个同级模块，会增加当前单进程工程复杂度；若所有本地查询都绕行 MCP，又会失去
强类型 API、增加协议开销并模糊权限边界。

## 决策

- `knowledge` 是一个 Spring Modulith 一级业务模块；
- 摄取、检索、权限、持久化、对象存储和向量适配都位于 `knowledge.internal`；
- 同进程 Agent 通过 `KnowledgeQueryService` 查询；
- `knowledge` 定义 `ExternalEvidenceProvider` SPI；
- `mcp` 实现该 SPI 接入外部知识，并调用 `KnowledgeQueryService` 对外暴露知识；
- 源码依赖只能是 `mcp → knowledge`，禁止 `knowledge → mcp`；
- 知识模块返回结构化证据，最终回答由 `orchestration` 生成。

## 权限约束

- `ActorContext` 由 Web/MCP 认证边界创建并显式传入；
- 租户、用户、部门和密级不能作为模型可填写参数；
- ACL 在向量和关键词检索之前执行；
- 外部知识服务必须在远端完成授权过滤；
- 远端不能传播身份时，只能作为公共知识源。

## 后果

正面：

- 本地启动保持简单；
- ACL、版本、审计和引用逻辑只有一份；
- MCP Client/Server 都能复用相同业务能力；
- 将来可以沿 `knowledge` 边界拆出服务。

负面：

- 单进程阶段摄取与在线检索共享资源，需要并发隔离；
- 外部 MCP 结果必须映射为内部 Evidence，存在适配成本；
- 真正拆成服务时需要新增版本化 REST/gRPC 契约。

