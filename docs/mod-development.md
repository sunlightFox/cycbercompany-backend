# Mod Development Contract

A Mod is a versioned, installable software surface for one domain. The platform
owns chat routing, identity, permissions, generic sessions and capability
dispatch. A Mod owns its domain UI, provider runtimes, operations, and resource
memory. The platform must not import a Mod's source adapters or understand its
domain state schema.

## Package Layout

```text
my-mod/
  manifest.json
  ui/                 # browser surface assets, if the Mod has UI
  runtime/            # optional isolated Worker/adapter declaration
  README.md
```

## Manifest

```json
{
  "id": "video-player",
  "version": "0.1.0",
  "surfaces": [
    {"id": "player", "role": "media-player", "presentations": ["docked", "fullscreen"]}
  ],
  "capabilities": [
    {"id": "media.search", "execution": "provider-runtime", "planning": true},
    {"id": "player.play", "execution": "direct", "planning": false}
  ],
  "memorySchemas": ["media_progress", "watch_history"],
  "permissions": ["network.media-source", "media.playback"],
  "runtime": {"protocol": "agentstudio.media.v1", "isolation": "process"}
}
```

Capability execution determines routing:

- `direct`: deterministic UI command; no model round-trip is needed for play,
  pause, seek or tab changes.
- `provider-runtime`: call an isolated provider for search or URL resolution.
- `agent`: use the Agent with the active Mod context for explanation, summaries
  or other language-heavy work.

## Surface Lifecycle

1. Agent classifies the user's request and selects an installed Mod.
2. The platform opens a `ModSession` and mounts the requested Surface as docked,
   floating or fullscreen.
3. Chat commands are translated to capability calls or direct `ModCommand`
   events. The UI does not need to parse natural language.
4. The Surface emits structured events such as `playback.progress`; the platform
   forwards generic state events to the Mod's `ModStateStore`, which decides what
   to persist and how to interpret it.
5. When the task ends, the platform suspends the Surface while retaining its
   session and memory according to policy.

## Runtime Rules

Untrusted DEX, native libraries, JavaScript Spider code and third-party binaries
must run outside the Spring JVM. A Worker receives JSON and returns normalized
JSON under the declared protocol. Workers should bind to loopback by default,
use a shared token for remote deployment, enforce timeouts and validate all URLs.

The Video Mod reference implementation is in
`src/main/resources/mods/video-player/`; its Worker bridge is
`scripts/tvbox-runtime-worker.py`.

## HTML Playback Contract

The browser Surface must only receive a platform-owned URL or an explicitly
approved playback page. A provider adapter never returns a browser-facing
secret, cookie, DEX result, or custom URL scheme.

Provider resolution has four meaningful outcomes:

| Result | Adapter response | HTML Surface behavior |
| --- | --- | --- |
| Direct media | `status=READY`, `streamUrl`, `mimeType` | Set `<video.src>` to the Demo gateway URL and play. |
| Website player | `status=WEBSITE_PAGE`, `playbackPageUrl` | Mount an iframe only when framing is allowed; otherwise open the source page. |
| Authentication | `status=LOGIN_REQUIRED` plus provider metadata | Ask the user to complete the provider's QR/web login, store a server-side session reference, then resolve again. |
| Unsupported | `status=UNAVAILABLE` or `RUNTIME_REQUIRED` | Keep the current player and ask for another source or runtime setup. |

The Demo gateway converts a direct provider URL into `/api/v1/media/stream/{token}`.
It forwards only approved upstream headers, handles byte ranges, and rewrites
HLS playlists, segment URLs, and key URLs. The original provider URL therefore
never becomes the HTML `src`.

Use this browser compatibility order:

1. MP4/WebM: native `<video>`.
2. HLS (`application/vnd.apple.mpegurl`): native HLS when the browser supports
   it; otherwise a bundled HLS/MSE player such as hls.js pointed at the gateway.
3. DASH: a bundled DASH/MSE player such as dash.js pointed at the gateway.
4. Non-standard or page-only playback: keep it as `WEBSITE_PAGE`; do not label it
   as directly playable. If broad browser support is mandatory, an isolated
   FFmpeg remux/transcode worker may expose a temporary fragmented-MP4 stream.

The last option is a compatibility fallback, not the default: it costs CPU,
adds latency, and may be impossible for DRM-protected content. DRM must use the
provider's EME/license flow and cannot be made playable by URL rewriting alone.
