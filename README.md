# CycberCompany

[中文](README.md) | [English](README.en.md)

CycberCompany 是一个 **local-first 的 Java AI Agent 后端**。它以模块化单体的方式组织会话、模型、Agent、工具、知识库、Skill、MCP 和本机节点，在一个 Spring Boot 进程中提供持久化 Run、SSE 流式事件、审批、审计和安全的本机执行能力。

项目适合用于本地开发、Agent 原型、工作流评审和学习大模型应用的工程化实现。配套前端位于独立仓库 `cycbercompany-web`。

## 主要能力

- **会话与运行编排**：会话消息持久化、同一会话 FIFO 队列、Run 状态机、取消、重试、审批恢复和可恢复的工作流检查点。
- **模型网关**：OpenAI-compatible `/chat/completions`，支持环境变量配置模型地址、模型名和密钥。
- **流式输出**：Run 事件持久化，并通过 SSE 提供实时推送和 `Last-Event-ID` 断线重放。
- **Agent 与工具**：Agent allow-list、统一 `ToolRouter`、工具审批、调用审计，以及后端、MCP、节点工具的稳定 binding。
- **知识库与 RAG**：按租户隔离的知识库，支持文本、HTML、Office Open XML 和文本型 PDF 导入、分块、关键词检索和可选向量检索。
- **Skill 生命周期**：GitHub/SkillHub 安装、YAML frontmatter 解析、启停、兼容性预检，以及按 SHA-256 固定的 Release 快照。
- **MCP 集成**：MCP 连接管理、工具发现、启用/禁用、调用审批和审计。
- **本机节点**：可选 Java 21 Companion，通过 WebSocket 执行文件、Shell、Git、浏览器和 Windows 桌面工具，并上传截图、下载文件和 Playwright Trace 等 Artifact。
- **安全边界**：本地 `LOCAL` 模式和远程 `TOKEN` 模式；workspace 是路径约束，不是操作系统沙箱；高风险工具默认需要审批。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言与运行时 | Java 21 LTS |
| Web | Spring Boot 4.1、Spring MVC、WebSocket、SSE |
| 持久化 | Spring Data JPA、文件型 H2 |
| 模块治理 | Spring Modulith |
| API 文档 | springdoc OpenAPI / Swagger UI |
| 文档解析 | Apache POI、PDFBox、Jsoup |
| 构建与测试 | Gradle Wrapper、JUnit 5 |

## 快速开始

### 环境要求

- JDK 21（`java -version`）
- Windows 用户可直接使用仓库内的 `gradlew.bat`；Linux/macOS 使用 `./gradlew`
- 一个 OpenAI-compatible 模型服务。默认配置使用 MiniMax-M3；也可以替换为其他兼容服务。

### 配置模型密钥

不要把密钥写入代码、YAML 或提交到 Git。PowerShell 示例：

```powershell
$env:EDGEFN_API_KEY="sk-..."
$env:EDGEFN_BASE_URL="https://api.edgefn.net/v1" # 可选
$env:EDGEFN_MODEL="MiniMax-M3"                    # 可选
```

也可以复制 `.env.example`，再由你的启动方式加载环境变量。

### 启动后端

```powershell
.\gradlew.bat bootRun
```

默认监听 `http://127.0.0.1:8080`，健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

首次启动会在 `./data` 创建 H2 数据库和 Skill/MCP 数据目录。测试使用独立的内存 H2，不会污染本地数据。

## 本机 Companion（可选）

个人本地模式会自动注册并启动本机 Companion，使 Agent 能在指定 workspace 中调用节点工具：

```powershell
.\scripts\start-personal-local.ps1 -Workspace D:\work\my-project
```

该脚本会启动匹配的前端仓库 `../cycbercompany-web`，并将后端置于 `http://127.0.0.1:8083`。停止服务：

```powershell
.\scripts\stop-personal-local.ps1
```

启动器会合并并发的 Companion 启动请求，避免重复拉起 Gradle/节点进程。Windows 默认通过 UAC 使用当前登录用户的管理员令牌；需要完全绕过 UAC 时才使用 `-NoElevation`。

需要独立分发时，可构建 Windows app-image 或 MSI：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\scripts\package-node-windows.ps1 -Type app-image `
  -Server http://127.0.0.1:8080 -Workspace D:\work\my-project
```

首次成功注册 workspace 后，打包版 Companion 会提供“Windows 登录后自动启动”开关。启用后只写入当前用户的 Startup 文件夹，使用已保存的节点凭据在后台重连；它不会创建 Windows 服务、修改注册表，也不会在用户确认 workspace 前启动。可在同一开关中关闭，或删除对应的 Startup 项。

Companion 不是操作系统沙箱。不要在未隔离的个人环境中运行不可信仓库或第三方 Skill；远程部署应使用低权限账户、容器或虚拟机，并启用 HTTPS/WSS 和 Token 认证。

## API 与示例

启动后访问：

- [Swagger UI](http://127.0.0.1:8080/swagger-ui)
- [OpenAPI JSON](http://127.0.0.1:8080/v3/api-docs)
- [健康检查](http://127.0.0.1:8080/actuator/health)

最小聊天流程：

```powershell
$conversation = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/v1/conversations `
  -ContentType application/json -Body '{"title":"Demo"}'

$run = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/v1/runs `
  -ContentType application/json `
  -Body (@{ conversationId = $conversation.id; text = "你好，介绍一下这个项目" } | ConvertTo-Json)

Invoke-RestMethod "http://127.0.0.1:8080/api/v1/runs/$($run.runId)"
```

常用接口包括：

```text
POST /api/v1/conversations                  创建会话
POST /api/v1/runs                           创建并排队 Run（202 Accepted）
GET  /api/v1/runs/{id}/events               SSE 事件流
GET  /api/v1/runs/{id}/workflow             工作流检查点
POST /api/v1/knowledge-search               知识库检索
POST /api/v1/web-search                     联网搜索
GET  /api/v1/skills                         Skill 目录
GET  /api/v1/mcp-connections                 MCP 连接
GET  /api/v1/nodes                          注册节点
```

## 配置速查

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8080` | HTTP 端口 |
| `APP_DATA_DIR` | `./data` | H2、Skill、MCP 和 Artifact 数据目录 |
| `EDGEFN_API_KEY` | 空 | 默认模型服务密钥 |
| `EDGEFN_BASE_URL` | `https://api.edgefn.net/v1` | OpenAI-compatible 服务地址 |
| `EDGEFN_MODEL` | `MiniMax-M3` | 默认模型名 |
| `TAVILY_API_KEY` | 空 | 存在时自动启用 Tavily Web Search |
| `APP_EXECUTION_ALLOW_NODES_ONLY` | `false` | 是否允许仅节点执行模式 |

远程或共享部署会开放 API 和节点注册，请仅通过受信任的 HTTPS/WSS 入口、网络边界或反向代理暴露服务。

## 开发与测试

```powershell
.\gradlew.bat test
```

建议先阅读：

- [新开发者指南](docs/new-developer-guide.md)：从启动、模块到一次 Run 的代码入口。
- [API 与调用链指南](docs/api-and-call-chain-guide.md)：Swagger 分组和 Controller → Service → Repository 链路。
- [总体架构](docs/architecture.md)：模块化单体、边界和技术取舍。
- [Agent v2 架构](docs/agent-v2-architecture.md)：Agent 定义、版本、草稿、发布和评测。
- [节点执行架构](docs/node-execution-architecture.md)：节点协议、审批、Artifact 和安全策略。
- [编码评测说明](docs/coding-evaluation.md)：交付证据和评测场景。

代码布局：

```text
src/main/java/io/github/yourname/cycbercompany/
├── agent/          Agent 定义与运行时
├── conversation/   会话和消息
├── model/          模型目录与网关
├── orchestration/  Run、队列、Agent Loop、事件
├── tool/           工具注册、路由与审批
├── knowledge/      知识库与检索
├── skill/          Skill 安装与 Release
├── mcp/            MCP 连接与工具
├── node/           节点注册、调用和恢复
└── web/            REST、SSE 和 OpenAPI
```

## 许可证

仓库当前未包含许可证文件。除非另有书面说明，代码版权归项目维护者所有；再分发前请先补充适用的许可证。
