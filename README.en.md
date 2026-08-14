# CycberCompany

[中文](README.md) | [English](README.en.md)

CycberCompany is a **local-first Java backend for AI agents**. It is a modular monolith that brings conversations, models, agents, tools, knowledge bases, Skills, MCP integrations, and local execution nodes into one Spring Boot process. Runs are durable, streamed over SSE, auditable, approval-aware, and designed for safe local development.

The companion frontend lives in a separate repository named `cycbercompany-web`.

## Highlights

- **Conversation and run orchestration**: persistent messages, per-conversation FIFO queues, run state transitions, cancellation, retry, approval resume, and durable workflow checkpoints.
- **Model gateway**: an OpenAI-compatible `/chat/completions` gateway with environment-based endpoint, model, and secret configuration.
- **Streaming events**: persisted run events with live SSE delivery and `Last-Event-ID` replay after reconnects.
- **Agents and tools**: agent allow-lists, a unified `ToolRouter`, approval policies, invocation audit, and stable bindings for backend, MCP, and node tools.
- **Knowledge and RAG**: tenant-scoped knowledge bases with text, HTML, Office Open XML, and text-based PDF ingestion, chunking, keyword retrieval, and optional vector retrieval.
- **Skill lifecycle**: GitHub/SkillHub installation, YAML frontmatter parsing, enable/disable controls, compatibility preflight, and SHA-256 addressed immutable release snapshots.
- **MCP integrations**: connection management, tool discovery, enable/disable controls, approvals, and call audit.
- **Local companion node**: an optional Java 21 process that exposes file, Shell, Git, browser, and Windows desktop tools over an authenticated WebSocket protocol, with artifact upload for screenshots, downloads, and Playwright traces.
- **Security boundaries**: local `LOCAL` mode and remote `TOKEN` mode; workspace paths constrain tool inputs but are not an OS sandbox; high-risk tools remain approval-gated by default.

In `LOCAL` mode, conversations are temporarily isolated by the request IP (`tenant=local`, `user=ip:<address>`) so a shared demo does not show one browser's chat history to another. This is only a demonstration boundary: users behind the same NAT/proxy share an IP and therefore share history. Replace `CurrentActorProvider` with the authenticated principal when login is introduced. The bundled static proxy forwards the client IP in `X-Forwarded-For`.

## Technology

| Area | Technology |
| --- | --- |
| Runtime | Java 21 LTS |
| Web | Spring Boot 4.1, Spring MVC, WebSocket, SSE |
| Persistence | Spring Data JPA, file-based H2 |
| Module governance | Spring Modulith |
| API documentation | springdoc OpenAPI / Swagger UI |
| Document parsing | Apache POI, PDFBox, Jsoup |
| Build and tests | Gradle Wrapper, JUnit 5 |

## Quick start

### Prerequisites

- JDK 21 (`java -version`)
- Gradle is not required: use `gradlew.bat` on Windows or `./gradlew` on Linux/macOS
- An OpenAI-compatible model provider. MiniMax-M3 is the seeded default, but any compatible provider can be configured.

### Configure the model

Keep secrets out of source files and YAML. PowerShell example:

```powershell
$env:EDGEFN_API_KEY="sk-..."
$env:EDGEFN_BASE_URL="https://api.edgefn.net/v1" # optional
$env:EDGEFN_MODEL="MiniMax-M3"                    # optional
```

`.env.example` lists the supported variables for local setups.

### Start the backend

```powershell
.\gradlew.bat bootRun
```

The default address is `http://127.0.0.1:8080`:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

The first start creates the H2 database and Skill/MCP data directories under `./data`. Tests use an isolated in-memory H2 database.

## Optional local companion

Personal-local mode registers and starts the companion so an agent can use node tools in a selected workspace:

```powershell
.\scripts\start-personal-local.ps1 -Workspace D:\work\my-project
```

This expects the matching frontend checkout at `../cycbercompany-web`, starts the local stack, and publishes the backend at `http://127.0.0.1:8083`. Stop it with:

```powershell
.\scripts\stop-personal-local.ps1
```

Concurrent browser requests share one in-progress companion launch, preventing duplicate Gradle/node processes. Windows startup uses UAC for the current signed-in user by default; use `-NoElevation` only when that is intentional.

For distribution, build a self-contained Windows app-image or MSI:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\scripts\package-node-windows.ps1 -Type app-image `
  -Server http://127.0.0.1:8080 -Workspace D:\work\my-project
```

After the first successful workspace registration, the packaged Companion offers an opt-in “Start automatically after Windows sign-in” control. It creates an entry only in the current user's Startup folder and reconnects with the stored node credential; it does not create a Windows service, edit the registry, or start before the user confirms a workspace. The control can be disabled from the Companion or by removing the clearly named Startup entry.

The companion is not an OS sandbox. Do not run untrusted repositories or third-party Skill scripts on an unisolated personal machine. Remote deployments should use a low-privilege account, container, or VM, plus HTTPS/WSS and token authentication.

## API and examples

Once the backend is running:

- [Swagger UI](http://127.0.0.1:8080/swagger-ui)
- [OpenAPI JSON](http://127.0.0.1:8080/v3/api-docs)
- [Health check](http://127.0.0.1:8080/actuator/health)

Minimal chat flow:

```powershell
$conversation = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/v1/conversations `
  -ContentType application/json -Body '{"title":"Demo"}'

$run = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/v1/runs `
  -ContentType application/json `
  -Body (@{ conversationId = $conversation.id; text = "Introduce this project" } | ConvertTo-Json)

Invoke-RestMethod "http://127.0.0.1:8080/api/v1/runs/$($run.runId)"
```

Common endpoints:

```text
POST /api/v1/conversations                  create a conversation
POST /api/v1/runs                           create and queue a Run (202 Accepted)
GET  /api/v1/runs/{id}/events               SSE event stream
GET  /api/v1/runs/{id}/workflow             workflow checkpoint
POST /api/v1/knowledge-search               knowledge retrieval
POST /api/v1/web-search                     web search
GET  /api/v1/skills                         Skill catalog
GET  /api/v1/mcp-connections                 MCP connections
GET  /api/v1/nodes                          registered nodes
```

## Configuration reference

| Variable | Default | Description |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `APP_DATA_DIR` | `./data` | H2, Skill, MCP, and artifact storage |
| `EDGEFN_API_KEY` | empty | Default model provider secret |
| `EDGEFN_BASE_URL` | `https://api.edgefn.net/v1` | OpenAI-compatible endpoint |
| `EDGEFN_MODEL` | `MiniMax-M3` | Default model name |
| `TAVILY_API_KEY` | empty | Enables Tavily web search when present |
| `APP_EXECUTION_ALLOW_NODES_ONLY` | `false` | Allow node-only execution mode |

Shared and remote deployments expose the API and open node registration. Restrict access with a trusted HTTPS/WSS entry point, network boundary, or reverse proxy.

## Development and tests

```powershell
.\gradlew.bat test
```

Further reading:

- [New developer guide](docs/new-developer-guide.md)
- [API and call-chain guide](docs/api-and-call-chain-guide.md)
- [Architecture](docs/architecture.md)
- [Agent v2 architecture](docs/agent-v2-architecture.md)
- [Node execution architecture](docs/node-execution-architecture.md)
- [Coding evaluation](docs/coding-evaluation.md)

Repository modules live under `src/main/java/io/github/yourname/cycbercompany/`: `agent`, `conversation`, `model`, `orchestration`, `tool`, `knowledge`, `skill`, `mcp`, `node`, and `web`. The optional Java node is in `cycbercompany-node-java/`.

## License

This repository currently has no license file. Unless otherwise stated in writing, the source remains “all rights reserved”; add an appropriate license before redistribution.
