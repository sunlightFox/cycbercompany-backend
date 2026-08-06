# Spring Agent Studio

Spring Agent Studio is a local-first Java backend for building and reviewing AI agent systems. It is designed as a modular monolith: one Spring Boot process, clear module boundaries, durable run state, SSE events, tenant-aware knowledge retrieval, and an OpenAI-compatible model gateway.

## 中文新手学习手册

如果你是第一次学习这个项目，建议先阅读
[docs/new-developer-guide.md](docs/new-developer-guide.md)。这份手册按“启动项目、理解模块、
跟踪一次 Run、理解模型/工具/知识库/Skill/MCP/节点”的顺序整理了代码入口和 Mermaid 流程图。

接口学习和链路调试建议继续阅读
[docs/api-and-call-chain-guide.md](docs/api-and-call-chain-guide.md)。启动后端后可打开
`http://127.0.0.1:8080/swagger-ui` 查看 Swagger UI，或访问
`http://127.0.0.1:8080/v3/api-docs` 获取 OpenAPI JSON。

## Node Execution Safety

The optional Java node runs file, Shell, Git, browser, and desktop actions on the
machine where the node process is installed. A configured workspace constrains
the paths accepted by the built-in tools, but it is **not an operating-system
sandbox**: child processes still inherit the node user's filesystem permissions,
environment, and network access. Use a dedicated low-privilege account or an OS
sandbox/container/VM before running untrusted repositories or third-party Skill
scripts. Remote deployment also requires authenticated HTTPS/WSS configuration;
unsafe remote-listening settings are rejected at startup.

## Trusted Sandbox Routing

In `NODES_ONLY` mode, an administrator can mark a registered node as `SANDBOX`
and attach up to 16 lowercase scheduling labels (for example `linux`, `java-21`,
or `playwright`) through `PATCH /api/v1/nodes/{id}`. A run may then provide
`nodeId: "auto"`, or omit `nodeId` and provide `nodeLabels`, to select an online
sandbox whose labels and explicitly requested node tools match. The resolved node
ID is saved in the immutable Run snapshot. Equivalent matching sandboxes are
selected in-process by round robin for new Runs; a restart may reset the cursor
but never changes the node already pinned to an existing Run.

`MANAGED_LOCAL` and ordinary `REGISTERED` nodes are deliberately excluded from
this automatic pool. System/desktop actions keep the stricter behavior: if more
than one personal computer is available, the caller must select one explicitly.
Nodes cannot declare themselves trusted through heartbeats or capability reports;
only the management API can assign `SANDBOX` and its labels.

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
- Skill Bundle 按 Release SHA-256 确定性生成，节点下载后同时校验 ZIP 摘要与解压目录树摘要。
- `skill.resource.read` 只读取分析阶段列入白名单的 references/templates/assets；脚本入口固定在不可变 binding 中。
- 第三方 Skill 脚本默认不执行；仅在显式启用 Docker Runtime 且本机镜像已存在时，才在禁网、只读、限额容器中执行。
- 浏览器截图和 Playwright Trace 通过节点认证的 Artifact HTTP API 上传，WebSocket 只返回摘要锁定的下载引用。
- Node browser actions include snapshot refs, multi-tab switching, bounded download/upload, hover, keyboard press, native select selection, and explicit alert/confirm/prompt handling.
- After a browser action that can change page state, the delivery gate requires a replayable Trace and a successful `browser.verify` performed after the final interaction; opening a page, taking a snapshot, or exporting a Trace alone is not treated as functional verification.
- `browser.verify` can additionally assert an observed response status and API path (`responseStatus` with optional `urlContains`, or `responseUrlContains`). Response assertions are scoped to requests observed after the latest page action, so an old successful request cannot verify a later interaction. The node keeps only bounded response metadata and strips URL query parameters; it never exposes response bodies, headers, cookies, or credentials.
- `browser.wait_response` waits for a matching status and/or query-free URL after the latest page action, making asynchronous frontend-to-backend checks deterministic. It returns only bounded response metadata; `browser.verify` remains the final auditable delivery assertion.
- If the task explicitly asks for a full-stack / frontend-backend integration test (including `前后端` or `联调`), the delivery gate also requires a passed post-interaction `responseStatus` or `responseUrlContains` check. A visible success message alone cannot finish that task.
- Windows system mode exposes approval-protected desktop window snapshots, UI Automation control metadata/actions, keyboard input, and bounded text clipboard operations.
- Windows system mode exposes approval-protected `system.software.query`, `system.software.install`, and `system.software.uninstall` tools backed by exact `winget` IDs. These tools do not accept arbitrary command lines, installer override arguments, hash bypasses, reboot allowances, display-name searches, or shell syntax, and they do not bypass Windows ACLs, protected services, file locks, vendor uninstallers, or reboot requirements.
- Windows system mode exposes approval-protected `system.service.query`, `system.service.stop`, and `system.service.set_start_mode` tools for exact Windows service names. They are intended for controlled preflight/remediation around uninstall blockers, but cannot bypass `NOT_STOPPABLE` service semantics, protected services, security software, or reboot requirements.
- Windows system mode exposes approval-protected `system.os_process.query` and `system.os_process.terminate` tools for exact Windows process image names, such as `QQPCTray.exe`. Termination requires explicit process IDs from a prior query or `allMatching=true`; it does not expose arbitrary `taskkill`, PowerShell, command-line filters, paths, or wildcards.
- Windows system mode exposes `system.privilege.query` to report whether the node process is running as an administrator token or LocalSystem. It is a read-only self-check used before system-level remediation.
- Windows system mode exposes `system.uninstall.preflight` to gather privilege, package, service, and process facts into one read-only remediation snapshot before uninstalling a Windows package.
- Windows system mode exposes approval-protected `system.uninstall.execute` as the guided remediation path: it runs preflight, optionally stops the exact service, optionally terminates exact process IDs, then retries one exact `winget` uninstall. It still cannot bypass Windows ACLs, protected services/processes, vendor UI requirements, or reboot requirements.
- Window activation and keyboard input require both `processId` and the latest `snapshotRevision` from `system.desktop.session.snapshot`; guessed or stale process targets are rejected locally before Windows receives input.
- Desktop UI Automation snapshots issue node-local `ref` values plus a monotonic `snapshotRevision`. Click and type require both values, invalidate them after any attempted action, and reject a live UI Automation lookup unless it has exactly one match; this prevents a stale or ambiguous text selector from silently targeting another control.
- `system.desktop.screenshot` captures the current primary display only after approval. It creates a PNG Artifact in the node-controlled artifact root; the existing artifact uploader sends an immutable reference and removes the node-local file before the tool result crosses WebSocket.
- Desktop UI Automation also exposes an approval-protected read-only verification action for confirming a target control still exists and is enabled after interaction.
- The delivery gate requires `system.desktop.ui.verify` or an approved `system.desktop.ui.read_value` after the final approved UI Automation click or type. Evidence recorded before a later click/type cannot prove the resulting Windows UI state.
- For approved desktop form checks, `system.desktop.ui.read_value` confirms one bounded non-password `ValuePattern` value after typing. It rejects password controls locally and remains approval-protected because ordinary field values may still be sensitive.
- Coding navigation includes bounded `project.symbols` declaration indexing and `project.references` candidate-usage lookup for common source languages. Complete Java files use the JDK compiler AST to exclude comments and string literals; incomplete Java and other languages use a bounded lexical fallback. Every result still requires `fs.read` review before edits.
- `fs.write` and `fs.apply_patch` stage complete replacement content beside the target file before moving it into place; a replacement failure therefore preserves the original source instead of truncating it.
- Build feedback includes read-only `project.diagnose` parsing for common compiler/test formats, plus `process.logs` for bounded stdout/stderr tails from a managed development process.
- `process.wait_http` provides bounded readiness evidence for a node-managed local development server. It performs only a redirect-disabled GET to literal `localhost`, `127.0.0.1`, or `::1`, discards the response body, and rejects credentials, query parameters, fragments, and remote addresses.
- Coding delivery includes read-only `git.review`: a bounded staged/unstaged/untracked file summary that requires each changed path to be inspected with `git.diff` or `fs.read`; `git.diff` supports `staged=true` for the staged diff and it never substitutes for running tests.
- The server-side delivery gate accepts code changes only when the final write is followed by `git.review`, review evidence for every changed path, and a successful build/test/lint/typecheck/HTTP verification. Earlier reads or reviews cannot prove the final state.
- Persistent orchestration checkpoints are queryable through `GET /api/v1/runs/{id}/workflow` without exposing raw tool arguments or page contents.
- Node reconnect recovery reconciles only persisted journal status by invocation ID, tool name, argument digest, and dispatch attempt. It never replays an uncertain side-effecting tool call.
- `POST /api/v1/runs/{id}/reconcile` lets an operator request the same safe Journal status check for one Run while a node is online; it sends only `tool.status` and reports unavailable nodes without changing command state.
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

### Personal local mode

Personal local is the default execution mode. Start it through the host-side launcher so
the local companion is registered automatically and can act on the signed-in user's desktop:

```powershell
.\scripts\start-personal-local.ps1 -Workspace D:\work\my-project
```

This command expects the matching frontend repository at `..\spring-agent-studio-web`. It starts
that repository's development compose stack, publishes the backend on `http://127.0.0.1:8083`,
and then starts the host-side local companion against that loopback API. Use
`.\scripts\stop-personal-local.ps1` to shut both down manually. The companion remains a separate
process and connects to the backend through the normal node protocol. Do not add it to Docker
Compose: a container would operate on the container filesystem, not the user's desktop.
On Windows, this launcher requests administrator approval through UAC by default, so the local
companion inherits the current user's elevated token. This is still the signed-in user, not the
Windows `LocalSystem` account. Choose the same Windows account in the UAC prompt; personal-local
startup and shutdown reject elevation as a different administrator user so token, state, desktop,
and workspace paths do not silently switch accounts. Use `-NoElevation` only when intentionally
running without UAC.
The loopback launcher used by the web UI also starts the local companion through UAC when it is
not already elevated, so UI-started personal-local nodes follow the same privilege behavior.
When `APP_SECURITY_MODE=TOKEN` is enabled, set `AGENT_STUDIO_API_TOKEN` in the current process,
user environment, machine environment, or compose environment before starting. The launcher reads
those scopes and passes the token through process environment inheritance. If UAC elevation would
lose a process-scoped token, the startup script and loopback launcher hand it to the elevated child
through a restricted temporary file and delete that file after reading it; they do not write the
token to state files, logs, or command-line arguments.
`start-local` already registers the companion in `SYSTEM` capability mode, which means the node
can address paths the current Windows user can access; it does not bypass Windows ACLs or protected
services such as `QQPCRTP`.
Switching the execution mode in the UI exposes registered-node selection and management; the
default personal-local UI does not expose node terminology.
The Nodes page also provides a Disconnect action for the managed local executor when you want to
close the companion from the server side.

### Packaged Windows companion

Build a self-contained companion when the user should not need Gradle or a separately installed
JDK. On its first launch, the packaged GUI waits for the user to confirm the project directory;
after that first successful connection it reconnects automatically when opened and preserves the
configured server and workspace:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\scripts\package-node-windows.ps1 `
  -Type app-image `
  -Server http://127.0.0.1:8080 `
  -Workspace D:\work\my-project
```

Use `-Type msi` to create an installer with Start menu and desktop shortcuts. Pass `-ManualStart`
when the user should explicitly press Start on every launch.

Docker Compose deployment files keep the executor service behind the `local-executor`
profile; use `docker compose --profile local-executor up -d` only when you explicitly
want the bundled executor service to start with the server.

The compose files under `deploy/` are for release-bundle layouts that contain packaged
`backend` and `frontend` build contexts. For day-to-day source checkout development, use
`.\gradlew.bat bootRun` for the backend alone or `.\scripts\start-personal-local.ps1` for the
full frontend/backend/local-companion flow.

## Frontend Repository

The matching frontend lives in a separate repository named
`spring-agent-studio-web`. It is adapted from the MIT-licensed
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
POST /api/v1/conversations/{id}/archive
POST /api/v1/conversations/{id}/attachments

GET  /api/v1/models
POST /api/v1/models
GET  /api/v1/approval-modes

GET  /api/v1/agents
GET  /api/v1/tools
GET  /api/v1/skill-marketplace
GET  /skill-marketplace
GET  /api/v1/skill-repositories
POST /api/v1/skill-repositories/search
POST /api/v1/skill-repositories/discover
GET  /api/v1/skill-registries/clawhub/search
GET  /api/v1/mcp-repositories                 # MCPMarket.cn only
POST /api/v1/mcp-repositories/search           # MCPMarket.cn only
POST /api/v1/mcp-connections/install            # one-click remote install

POST /api/v1/knowledge-bases
GET  /api/v1/knowledge-bases
POST /api/v1/knowledge-bases/{id}/documents
POST /api/v1/knowledge-bases/{id}/documents/upload
POST /api/v1/knowledge-bases/{id}/documents/batch-upload
POST /api/v1/knowledge-search

POST /api/v1/web-search

POST /api/v1/runs
GET  /api/v1/runs/{id}
POST /api/v1/runs/{id}/reconcile
GET  /api/v1/runs/{id}/workflow
GET  /api/v1/runs/{id}/events
GET  /api/v1/runs/{id}/coding-evidence
GET  /api/v1/runs/{id}/coding-quality
GET  /api/v1/runs/{id}/coding-evaluation?scenario=minimal-full-stack
GET  /api/v1/conversations/{id}/queue
```

`coding-evaluation` is a read-only, scenario-specific report generated from
persisted Run state, node invocation audit records, and lifecycle events. The
five supported scenario values are documented in `docs/coding-evaluation.md`.
It measures delivery evidence, not business correctness, and never returns raw
commands, terminal output, source code, or node absolute paths.

## Run Approval Mode

The run composer can render its approval selector from `GET /api/v1/approval-modes` and send
the selected `approvalMode` with `POST /api/v1/runs`:

```json
{
  "conversationId": "conversation-id",
  "text": "Inspect the project and run its tests.",
  "approvalMode": "full-access"
}
```

The accepted stable values are:

- `on-request` (default): each approval-protected tool pauses for a decision.
- `auto-approve`: low- and medium-risk tools run automatically; high-risk tools still pause.
- `full-access`: no human approval pause for tools in this Run.

The selected mode is persisted in the immutable Run snapshot and is retained when an
approval-paused run is resumed. `full-access` does not disable the enabled-tool allow list,
workspace policy, server-side authorization, or the tool invocation audit trail.

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

`GET /api/v1/runs/{id}/workflow` returns the persisted orchestration checkpoint: current phase,
workspace scope, bounded plan summary, recent tool name, success/failure counters, and the last
bounded error. Tool arguments, secrets, raw page contents, and long command output are intentionally
excluded from this control-plane view. Its `executionTask` field also reports the safe worker summary
(`READY`/`RUNNING`/`WAITING_APPROVAL`/terminal status, attempt count, and lease expiry) without
returning the private lease token or raw worker errors. `UNKNOWN` is exposed as `recoveryRequired=true`;
it means a side-effecting worker outcome needs reconciliation and must not be replayed automatically.
When its node is available, `POST /api/v1/runs/{id}/reconcile` requests a fresh `tool.status` from
the node Journal. The response contains only invocation ID, node ID, tool name, persisted status,
and whether the request was sent; it never includes raw arguments or results, and it never retries
the original tool call.

## Skill 版本与 Run 快照

`data/skills` 保存当前活动安装，方便用户查看、升级、启用或禁用 Skill；
`data/skill-releases` 保存按 SHA-256 寻址的不可变版本。创建 Run 时，服务端会先校验
Skill 是否存在且已启用，再把用户选择的顺序、Git commit 和内容摘要写入 Run。

worker 执行时只根据 Run 中保存的绑定读取不可变 Release。因此即使活动 Skill 后来被
升级、修改或卸载，旧 Run 仍使用原来的 `SKILL.md`，不会在后台静默改变行为。Release
读取前会再次计算摘要，被篡改的内容会直接拒绝。

`SKILL.md` 会进入模型上下文；文本资源通过 `skill.resource.read` 按需读取。脚本不会因为安装
Skill 而自动执行，也不会静默下载依赖。只有同时满足以下条件时才会暴露 `skill.script.run`：

1. 设置 `AGENT_STUDIO_SKILL_RUNTIME=docker`；
2. 节点本机已有对应运行时镜像；
3. Skill 兼容预检通过；
4. 用户批准绑定了具体 Release、脚本入口、参数摘要和节点的调用。

Docker Runtime 固定禁用网络、使用只读根文件系统和只读 Bundle，并限制 CPU、内存、PID、
执行时间与输出大小。第一版支持 Python、JavaScript/MJS 和 Shell；不会隐式 `docker pull`。
可通过 `AGENT_STUDIO_SKILL_PYTHON_IMAGE`、`AGENT_STUDIO_SKILL_NODE_IMAGE` 和
`AGENT_STUDIO_SKILL_SHELL_IMAGE` 覆盖默认镜像。

节点将 Bundle 缓存和每个 Run 的可写 workspace 放在 `%USERPROFILE%/.agent-studio-node/data`，
不放入用户项目目录。缓存命中时仍会重新计算摘要；缓存或 Release 被篡改后执行直接失败。

## Skill Bundle 与 Artifact API

```text
GET  /api/v1/node/skill-bundles/{skillId}/{releaseHex}
POST /api/v1/node/artifacts
GET  /api/v1/artifacts/{artifactId}
```

前两个节点接口使用 `X-Agent-Studio-Node-Id` 和 `Authorization: Bearer <nodeSecret>`；
用户下载 Artifact 时按当前认证 Actor 的 tenant 校验。截图、Trace 等文件上传成功后，节点删除
本地临时文件，WebSocket 结果移除 `artifactPath`，只保留 `artifactId/digest/size/downloadUrl`。

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

Web search is invoked automatically when the run intent requires current or external information.
Tavily is selected automatically when `TAVILY_API_KEY` is present, and the free GDELT DOC index can
supplement news candidates with timestamps.
Broad current queries are expanded into at most three complementary searches and executed in
parallel, with GDELT added for news intent. Candidates are merged, deduplicated,
and diversified. Relative index times and calendar dates embedded in news URLs are accepted as
freshness signals. Result pages are read directly first; the optional Jina Reader fallback is used
only when direct page extraction fails. Publication times are collected from the news index, HTML
metadata, and JSON-LD before a current-news result is accepted. There is no RSS fallback.

The public GDELT endpoint is globally throttled to one request every five seconds, as required by
its service. A repeated query can return `CACHED`; a different query during that cooldown reports
GDELT as `SKIPPED` while Tavily and page verification remain available.

Set the Tavily key before starting the backend:

```powershell
$env:TAVILY_API_KEY="tvly-..."
```

The backend uses Tavily automatically when `TAVILY_API_KEY` is present.
Legacy web-search settings remain in `application.yml` only for compatibility with older local
deployments.

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/web-search `
  -ContentType application/json `
  -Body '{"query":"assistant-ui GitHub","limit":3}'
```

Pass `trace: true` to inspect the chosen intent, provider status, duplicates removed,
domain-diversity filtering, and page-verification counts. Without it, the endpoint continues
to return the result array for compatibility.

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/web-search `
  -ContentType application/json `
  -Body '{"query":"latest Java security news","limit":5,"mode":"AUTO","freshness":"WEEK","trace":true}'
```

The optional request fields are `mode`, `freshness` (`ANY`, `DAY`, `WEEK`, `MONTH`),
`includeDomains`, `excludeDomains`, and `trace`. Per-domain limits, query fan-out, short-lived
cache, and page-reader safety limits are configured under `app.web-search` in `application.yml`.

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
