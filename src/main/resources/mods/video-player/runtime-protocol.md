# Video Mod Provider Runtime Protocol

The Spring backend never loads TVBox DEX, JNI, or JavaScript Spider code. An isolated
worker may implement the following JSON endpoint:

`POST /v1/media/search`

The reference worker binds to `127.0.0.1` by default. When it is deployed on a
different host, set the same `VIDEO_DEMO_RUNTIME_TOKEN` on the Video Demo and worker;
the platform sends it in `X-Media-Runtime-Token`.

Request:

```json
{
  "operation": "media.search",
  "query": "机器猫",
  "sourceUrl": "http://fty.xxooo.cf/tv",
  "configDigest": "sha256:...",
  "sourceKeys": ["csp_demo"]
}
```

Response must be a normalized `MediaSearchView` JSON object. The Video Demo owns
source-specific Spider execution, playback memory and UI state; the platform only
owns generic Mod lifecycle, identity and capability dispatch.

The same endpoint also accepts `operation: "media.resolvePlayback"` and returns a
normalized playback object with `status`, `mediaId`, `sourceId`, `episodeId`,
`streamUrl`, `mimeType`, `durationMs`, `subtitleUrls`, and `message` fields.
It may include `requestHeaders` (`Referer`, `Origin`, `User-Agent`, `Cookie`, or
`Authorization`) for the Demo-owned media gateway. Those headers are never sent
to browser JavaScript; the gateway applies them to upstream media requests.

The repository includes a Python 3.11+ bridge worker at
`scripts/tvbox-runtime-worker.py`. It never downloads or imports a Spider.
Instead, its operator configures `TVBOX_RUNTIME_ADAPTER` as a JSON argv array
for a reviewed compatibility executable. The worker sends the request JSON to
that executable over stdin and accepts one normalized JSON response on stdout.
This lets a sandboxed Android/TVBox-compatible adapter execute protected DEX or
native Spider code without giving that code access to the Spring process.

Example local start:

```powershell
$env:TVBOX_RUNTIME_ADAPTER = '["C:\\approved-tvbox-adapter\\adapter.exe","--stdio"]'
$env:VIDEO_DEMO_RUNTIME_TOKEN = 'replace-with-a-random-secret'
& 'C:\\Path\\To\\Python311\\python.exe' scripts/tvbox-runtime-worker.py --port 18120
```

Configure the Demo with `VIDEO_DEMO_RUNTIME_ENDPOINT=http://127.0.0.1:18120/v1/media/search`
and the same `VIDEO_DEMO_RUNTIME_TOKEN`. The adapter is responsible for the Android/
TVBox compatibility layer and must be packaged separately from this repository.

For Demo-managed lazy startup, set `VIDEO_DEMO_RUNTIME_COMMAND` to the same JSON
argv used to launch the Worker, and expose the adapter configuration to that
process:

```powershell
$env:TVBOX_RUNTIME_ADAPTER = '["py","-3.12","D:\ai\spring-agent-studio-backend\scripts\tvbox-compatible-adapter.py"]'
$env:VIDEO_DEMO_TVBOX_CONFIG = 'D:\ai\spring-agent-studio-backend\output\tvbox-config.json'
$env:VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT = 'http://127.0.0.1:18220/v1/execute'
$env:VIDEO_DEMO_RUNTIME_ENDPOINT = 'http://127.0.0.1:18120/v1/media/search'
$env:VIDEO_DEMO_RUNTIME_COMMAND = '["py","-3.12","D:\\ai\\spring-agent-studio-backend\\scripts\\tvbox-runtime-worker.py","--port","18120"]'
```

The Demo starts the Worker on the first Video Mod search/resolve request and
terminates it with the backend process. For manual startup use
`scripts/start-video-demo-runtime.ps1`.

## Protected `csp_*` Sources

`csp_App99Guard` (the configured `视界` / 茉莉来源) is a protected Android Spider:
the configured package contains DEX and native Guard libraries, not a website
that can be parsed by the HTML player. Use the shipped bridge
`scripts/tvbox-compatible-adapter.py` as `TVBOX_RUNTIME_ADAPTER`:

```powershell
$env:TVBOX_RUNTIME_ADAPTER = '["py","-3.12","scripts/tvbox-compatible-adapter.py"]'
$env:VIDEO_DEMO_TVBOX_CONFIG = 'D:\ai\spring-agent-studio-backend\output\tvbox-config.json'
$env:VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT = 'http://127.0.0.1:18220/v1/execute'
```

The bridge sends this request to the Android/TVBox-compatible engine. It never
downloads or runs the Spider itself:

```json
{
  "protocol": "agentstudio.tvbox.engine.v1",
  "operation": "media.resolvePlayback",
  "sourceId": "视界",
  "mediaId": "...",
  "episodeId": "1",
  "spiderRef": "https://...",
  "sources": [{"key": "视界", "api": "csp_App99Guard", "ext": "..."}]
}
```

The engine must return the normal Video Mod response, including an HTTP(S)
`streamUrl` and any required request headers. `/health` reports `READY` only
when the bridge command, the engine endpoint, and the external engine's
`runtime.health` response all report ready.
