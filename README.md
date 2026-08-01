# Spring Agent Studio

Spring Agent Studio is a local-first Java backend for building and reviewing AI agent systems. It is designed as a modular monolith: one Spring Boot process, clear module boundaries, durable run state, SSE events, tenant-aware knowledge retrieval, and an OpenAI-compatible model gateway.

## Node Execution Safety

The optional Java node runs file, Shell, Git, browser, and desktop actions on the
machine where the node process is installed. A configured workspace constrains
the paths accepted by the built-in tools, but it is **not an operating-system
sandbox**: child processes still inherit the node user's filesystem permissions,
environment, and network access. Use a dedicated low-privilege account or an OS
sandbox/container/VM before running untrusted repositories or third-party Skill
scripts. Remote deployment also requires authenticated HTTPS/WSS configuration;
unsafe remote-listening settings are rejected at startup.

## What Is Implemented

- Spring Boot 4.1 backend with Gradle Wrapper.
- Local H2 file storage and H2 console.
- Default `MiniMax-M3` model profile using an OpenAI-compatible `/chat/completions` API.
- Environment-variable based secret loading through `EDGEFN_API_KEY`.
- Conversation and message persistence.
- Conversation-scoped FIFO chat queue: messages in one conversation run in send order while separate conversations remain parallel.
- Agent run lifecycle: `QUEUED`, `RUNNING`, `WAITING_APPROVAL`, `SUCCEEDED`, `FAILED`.
- Durable run events with SSE replay through `Last-Event-ID`.
- Tenant-scoped knowledge bases with idempotent text ingestion and keyword retrieval.
- Tool catalog with low-risk local tools, including local time, knowledge search, and web search.
- Agent-side web search for current/external questions. Search results are injected as evidence and the model is asked to cite source URLs when used.
- GitHub Skill 安装、启用/禁用、完整 YAML frontmatter 解析和不可变 Release 快照。
- Run 会保存所选 Skill 的来源、commit 和 SHA-256，并把锁定版本的完整 `SKILL.md` 注入模型上下文。
- MCP 连接、工具发现、调用审计和审批边界。
- 统一 `ToolRouter`：后端、MCP 与节点工具使用稳定 binding，Agent 和 Run 权限只取交集。
- 不可变 `RunSpec`：Run 入队前固定 Agent 提示词、Skill、工具、节点、Actor 和策略摘要。
- Skill 兼容预检：缺失工具、运行时或节点 feature 时，在保存 Run 和调用模型前明确失败。
- Local security adapter that creates a trusted `ActorContext` from request headers.
- Tests using an isolated in-memory H2 database.

## Model Configuration

Do not put the API key directly in code or YAML. Set it in your shell:

```powershell
$env:EDGEFN_API_KEY="sk-..."
```

The default model profile is seeded on first startup:

```text
id: minimax-m3
provider: OPENAI_COMPATIBLE
baseUrl: https://api.edgefn.net/v1
modelName: MiniMax-M3
credentialRef: EDGEFN_API_KEY
```

You can override these values with:

```powershell
$env:EDGEFN_BASE_URL="https://api.edgefn.net/v1"
$env:EDGEFN_MODEL="MiniMax-M3"
```

## Run

```powershell
.\gradlew.bat bootRun
```

Health check:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

## Frontend Repository

The matching frontend lives in a separate repository named
`spring-agent-studio-frontend`. It is adapted from the MIT-licensed
[assistant-ui/assistant-ui](https://github.com/assistant-ui/assistant-ui)
minimal template and connects to this backend through:

```text
Browser
  -> Next.js /api/chat
  -> Spring POST /api/v1/conversations
  -> Spring POST /api/v1/runs
  -> Spring GET /api/v1/runs/{runId}/events
  -> assistant-ui thread
```

## Core APIs

```text
POST /api/v1/conversations
GET  /api/v1/conversations/{id}
POST /api/v1/conversations/{id}/attachments

GET  /api/v1/models
POST /api/v1/models

GET  /api/v1/agents
GET  /api/v1/tools

POST /api/v1/knowledge-bases
GET  /api/v1/knowledge-bases
POST /api/v1/knowledge-bases/{id}/documents
POST /api/v1/knowledge-bases/{id}/documents/upload
POST /api/v1/knowledge-bases/{id}/documents/batch-upload
POST /api/v1/knowledge-search

POST /api/v1/web-search

POST /api/v1/runs
GET  /api/v1/runs/{id}
GET  /api/v1/runs/{id}/events
GET  /api/v1/conversations/{id}/queue
```

## Chat Queue

`POST /api/v1/runs` immediately persists the user message and returns `202` with a `QUEUED` run,
its initial `queuePosition`, and the SSE URL. A position of `0` in `GET /api/v1/runs/{id}` means
the run currently owns the conversation queue; positive values identify pending order.

The first SSE event is `RUN_QUEUED` and the normal `RUN_STARTED` event arrives only when the run
reaches the front. `GET /api/v1/conversations/{id}/queue` returns the active run, pending runs,
and concise `guide.message` / `guide.cancelHint` copy intended for the chat composer. A run paused
for tool approval keeps its queue slot, so later messages never see a partially completed turn.
Cancelling a pending run removes only that item; cancelling an approval-paused run releases the
next queued message.

## Skill 版本与 Run 快照

`data/skills` 保存当前活动安装，方便用户查看、升级、启用或禁用 Skill；
`data/skill-releases` 保存按 SHA-256 寻址的不可变版本。创建 Run 时，服务端会先校验
Skill 是否存在且已启用，再把用户选择的顺序、Git commit 和内容摘要写入 Run。

worker 执行时只根据 Run 中保存的绑定读取不可变 Release。因此即使活动 Skill 后来被
升级、修改或卸载，旧 Run 仍使用原来的 `SKILL.md`，不会在后台静默改变行为。Release
读取前会再次计算摘要，被篡改的内容会直接拒绝。

当前 P0 只支持指令型 Skill：`SKILL.md` 会进入模型上下文，但 `scripts/` 中的文件绝不会
自动执行。脚本执行要等后续 Skill Runtime 完成 Bundle 校验、运行时预检、网络策略和审批。

## ToolRouter、RunSpec 与兼容预检

模型看到的函数名不是节点或 MCP 的原始调用地址。服务端先让各 `ToolProvider` 上报事实能力，
再由 `ToolRouter` 计算 Agent allow-list 与本次 Run `toolNames` 的交集。解析后的 binding 固定
Provider、节点或 MCP connection，模型只能填写 schema 允许的业务参数，不能通过参数切换执行目标。

Run 保存前会生成完整的不可变 `RunSpec`，包含 Agent 提示词和摘要、Skill Release、有效工具
binding、节点和工作区、附件上下文、能力/策略 revision，以及发起人的 tenant、user、role 和
scope。worker 只接收 `runId`，执行和审批恢复时从数据库读取并复核 `RunSpec` SHA-256；管理员
后来修改活动 Agent、Skill 或工具目录，不会静默改变已经排队的 Run。

Skill 的 `allowed-tools`、`requirements.tools`、`requirements.runtimes` 和
`requirements.features` 会在创建 Run 时与有效工具集及选中节点快照交叉检查。缺失项通过结构化
`CompatibilityReport` 返回，并且不会保存半成品 Run 或调用模型。网络声明只产生警告，不能给
Skill 自动扩权。

需要审批的 MCP 工具统一创建持久化审批记录，精确绑定 Run、tool call、binding、参数摘要、
工作区、请求人和有效期。审批接口为：

```text
GET  /api/v1/tool-approvals
POST /api/v1/tool-approvals/{id}/decision
```

非本地部署中申请人不能批准自己的调用。批准或拒绝后，服务端使用审批记录中的原参数恢复同一个
Run，而不是接受浏览器重新提交一份可能已经变化的工具参数。

## Knowledge document uploads

`POST /api/v1/knowledge-bases/{id}/documents/upload` accepts one multipart `file`. The
`batch-upload` variant accepts repeated multipart `files` parts, up to 20 files per request. Each
file is processed independently and the response includes a result for every file, including a
human-readable error when parsing fails. The request limit is 100 MB and each file remains limited
to 20 MB. Plain text, HTML, Office OpenXML (`docx`, `xlsx`, `pptx`) and text-based PDF files are
supported; scanned/image-only PDFs require OCR and may return an empty-text error.

## Attachments

Upload one or more files as multipart `files` parts to
`POST /api/v1/conversations/{id}/attachments`, then include the returned IDs in `attachmentIds`
when creating a run. Files are scoped to their tenant and conversation, stored under the local
application data directory, and limited to 20 MB each. Text-based attachments contribute a bounded
UTF-8 excerpt to the current model turn; image and binary attachments remain available to the chat
record without being injected as image bytes into a text-only model request.

## Web Search

Web search uses a self-hosted [SearXNG](https://github.com/searxng/searxng) JSON endpoint for
general discovery, plus the free GDELT DOC index for news candidates with timestamps. The included
SearXNG profile routes general searches to MWMBL, Bing Web, and Baidu, and news searches to Bing
News plus Reuters. Broad current
queries are expanded into at most three complementary searches and executed in parallel with
GDELT; provider results are cached briefly so immediate retries do not lose GDELT to its public
cooldown. Candidates are merged, deduplicated, and diversified. Relative index times such as
`7 hours ago` and calendar dates embedded in news URLs are accepted as freshness signals. Result pages are read directly
first; the optional Jina Reader fallback
is used only when direct page extraction fails. Publication times are collected from the news index,
HTML metadata, and JSON-LD before a current-news result is accepted. There is no RSS fallback.

The public GDELT endpoint is globally throttled to one request every five seconds, as required by
its service. A repeated query can return `CACHED`; a different query during that cooldown reports
GDELT as `SKIPPED` while SearXNG and page verification remain available.

Start the included local SearXNG service before starting the backend:

```powershell
docker compose -f docker-compose.searxng.yml up -d
```

The backend defaults to `http://localhost:8888`; set `SEARXNG_ENDPOINT` when it is hosted
elsewhere. The compose file binds SearXNG to localhost only. Change the generated secret in
`infra/searxng/settings.yml` before exposing it on a network.

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/web-search `
  -ContentType application/json `
  -Body '{"query":"assistant-ui GitHub","limit":3}'
```

Pass `trace: true` to inspect the chosen intent, SearXNG status, duplicates removed,
domain-diversity filtering, and page-verification counts. Without it, the endpoint continues
to return the result array for compatibility.

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/web-search `
  -ContentType application/json `
  -Body '{"query":"latest Java security news","limit":5,"mode":"AUTO","freshness":"WEEK","trace":true}'
```

The optional request fields are `mode`, `freshness` (`ANY`, `DAY`, `WEEK`, `MONTH`),
`includeDomains`, `excludeDomains`, and `trace`. SearXNG connection, per-domain limits,
query fan-out, short-lived cache, per-domain limits, and page-reader safety limits are configured
under `app.web-search` in `application.yml`.

The agent automatically searches the web when the user asks for terms such as
`联网`, `搜索`, `最新`, `新闻`, `GitHub`, `latest`, or `search`. Natural-language
instructions are converted into a compact query before searching; for example,
`搜索一下 assistant-ui GitHub 是什么，回答时带来源链接` searches for
`assistant-ui GitHub`.

## Minimal Demo Flow

Create a conversation:

```powershell
$conversation = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/conversations `
  -ContentType application/json `
  -Body '{"title":"Demo"}'
```

Create a run:

```powershell
$run = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/runs `
  -ContentType application/json `
  -Body (@{
    conversationId = $conversation.id
    text = "Hello, how are you?"
  } | ConvertTo-Json)
```

Read run state:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/runs/$($run.runId)"
```

Subscribe to events in a browser or SSE client:

```text
http://localhost:8080/api/v1/runs/{runId}/events
```

## Test

```powershell
.\gradlew.bat test
```

Tests use `src/test/resources/application.yml`, which points to an in-memory H2 database so local file data is not polluted.
