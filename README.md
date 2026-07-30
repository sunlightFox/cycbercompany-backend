# Spring Agent Studio

Spring Agent Studio is a local-first Java backend for building and reviewing AI agent systems. It is designed as a modular monolith: one Spring Boot process, clear module boundaries, durable run state, SSE events, tenant-aware knowledge retrieval, and an OpenAI-compatible model gateway.

## What Is Implemented

- Spring Boot 4.1 backend with Gradle Wrapper.
- Local H2 file storage and H2 console.
- Default `MiniMax-M3` model profile using an OpenAI-compatible `/chat/completions` API.
- Environment-variable based secret loading through `EDGEFN_API_KEY`.
- Conversation and message persistence.
- Agent run lifecycle: `CREATED`, `RUNNING`, `SUCCEEDED`, `FAILED`.
- Durable run events with SSE replay through `Last-Event-ID`.
- Tenant-scoped knowledge bases with idempotent text ingestion and keyword retrieval.
- Tool catalog with low-risk local tools, including local time, knowledge search, and web search.
- Agent-side web search for current/external questions. Search results are injected as evidence and the model is asked to cite source URLs when used.
- Placeholder boundaries for Skill and MCP modules, ready for later adapters.
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

GET  /api/v1/models
POST /api/v1/models

GET  /api/v1/agents
GET  /api/v1/tools

POST /api/v1/knowledge-bases
GET  /api/v1/knowledge-bases
POST /api/v1/knowledge-bases/{id}/documents
POST /api/v1/knowledge-search

POST /api/v1/web-search

POST /api/v1/runs
GET  /api/v1/runs/{id}
GET  /api/v1/runs/{id}/events
```

## Web Search

Web search is enabled by default and currently uses DuckDuckGo's HTML endpoint,
so it does not require another provider key:

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/web-search `
  -ContentType application/json `
  -Body '{"query":"assistant-ui GitHub","limit":3}'
```

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
