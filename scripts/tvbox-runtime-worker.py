#!/usr/bin/env python3
"""Isolated bridge for a reviewed TVBox-compatible media adapter.

The worker deliberately does not fetch, unpack, or execute Spider URLs. A
separate compatibility adapter is installed and reviewed by the operator, then
configured via TVBOX_RUNTIME_ADAPTER as a JSON argv array. Each request is
passed as a single JSON document over stdin; the adapter must emit one JSON
document on stdout following the Video Mod runtime protocol.
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import subprocess
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

MAX_REQUEST_BYTES = 512 * 1024
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
SUPPORTED_OPERATIONS = {"media.search", "media.resolvePlayback"}
HEALTH_OPERATION = "runtime.health"


def adapter_command() -> list[str]:
    raw = os.environ.get("TVBOX_RUNTIME_ADAPTER", "").strip()
    if not raw:
        return []
    value = json.loads(raw)
    if not isinstance(value, list) or not value or not all(isinstance(item, str) and item for item in value):
        raise ValueError("TVBOX_RUNTIME_ADAPTER must be a non-empty JSON argv array.")
    return value


def unavailable(request: dict[str, Any], message: str) -> dict[str, Any]:
    if request.get("operation") == HEALTH_OPERATION:
        return {"status": "DOWN", "ready": False, "message": message}
    if request.get("operation") == "media.search":
        return {"query": str(request.get("query") or ""), "status": "RUNTIME_UNAVAILABLE", "message": message,
                "items": [], "sourceKeys": list(request.get("sourceKeys") or [])}
    return {"status": "RUNTIME_UNAVAILABLE", "mediaId": str(request.get("mediaId") or ""),
            "sourceId": request.get("sourceId"), "episodeId": request.get("episodeId"), "streamUrl": None,
            "mimeType": None, "durationMs": 0, "subtitleUrls": [], "message": message}


def invoke_adapter(request: dict[str, Any]) -> dict[str, Any]:
    try:
        command = adapter_command()
    except (ValueError, json.JSONDecodeError):
        return unavailable(request, "TVBOX_RUNTIME_ADAPTER is invalid JSON.")
    if not command:
        return unavailable(request, "TVBox compatibility adapter is not configured.")
    try:
        completed = subprocess.run(
            command,
            input=json.dumps(request, ensure_ascii=False).encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=45,
            check=False,
        )
        if completed.returncode != 0:
            return unavailable(request, "TVBox compatibility adapter failed.")
        if len(completed.stdout) > MAX_RESPONSE_BYTES:
            return unavailable(request, "TVBox compatibility adapter response is too large.")
        response = json.loads(completed.stdout.decode("utf-8"))
        if not isinstance(response, dict):
            return unavailable(request, "TVBox compatibility adapter returned an invalid response.")
        return normalize(request, response)
    except subprocess.TimeoutExpired:
        return unavailable(request, "TVBox compatibility adapter timed out.")
    except (OSError, ValueError, json.JSONDecodeError):
        return unavailable(request, "TVBox compatibility adapter is unavailable.")


def normalize(request: dict[str, Any], response: dict[str, Any]) -> dict[str, Any]:
    """Apply stable result shapes before returning an adapter response to Spring."""
    if request["operation"] == HEALTH_OPERATION:
        return {
            "status": str(response.get("status") or "DOWN"),
            "ready": bool(response.get("ready")),
            "message": str(response.get("message") or ""),
        }
    if request["operation"] == "media.search":
        items = response.get("items") if isinstance(response.get("items"), list) else []
        safe_items = [item for item in items if isinstance(item, dict)][:100]
        return {"query": str(response.get("query") or request.get("query") or ""),
                "status": str(response.get("status") or "READY"), "message": str(response.get("message") or ""),
                "items": safe_items, "sourceKeys": list(response.get("sourceKeys") or request.get("sourceKeys") or [])}
    subtitles = response.get("subtitleUrls") if isinstance(response.get("subtitleUrls"), list) else []
    headers = response.get("requestHeaders") if isinstance(response.get("requestHeaders"), dict) else {}
    safe_headers = {
        str(key): str(value) for key, value in headers.items()
        if isinstance(key, str) and isinstance(value, str)
        and key.lower() in {"referer", "origin", "user-agent", "cookie", "authorization"}
    }
    return {"status": str(response.get("status") or "UNAVAILABLE"),
            "mediaId": str(response.get("mediaId") or request.get("mediaId") or ""),
            "sourceId": response.get("sourceId") if response.get("sourceId") is not None else request.get("sourceId"),
            "episodeId": response.get("episodeId") if response.get("episodeId") is not None else request.get("episodeId"),
            "streamUrl": response.get("streamUrl"), "mimeType": response.get("mimeType"),
            "durationMs": max(0, int(response.get("durationMs") or 0)),
            "subtitleUrls": [url for url in subtitles if isinstance(url, str)][:20],
            "message": str(response.get("message") or ""), "requestHeaders": safe_headers}


class Handler(BaseHTTPRequestHandler):
    server_version = "AgentStudioMediaRuntime/1.0"

    def do_GET(self) -> None:
        if self.path == "/health":
            try:
                configured = bool(adapter_command())
            except (ValueError, json.JSONDecodeError):
                configured = False
                self.respond(HTTPStatus.OK, {
                    "status": "UP", "adapterConfigured": False, "adapterReady": False,
                    "adapterMessage": "TVBOX_RUNTIME_ADAPTER is invalid JSON.",
                })
                return
            adapter = invoke_adapter({"operation": HEALTH_OPERATION}) if configured else {"ready": False}
            self.respond(HTTPStatus.OK, {
                "status": "UP",
                "adapterConfigured": configured,
                "adapterReady": bool(adapter.get("ready")),
                "adapterMessage": str(adapter.get("message") or ""),
            })
            return
        self.respond(HTTPStatus.NOT_FOUND, {"message": "Not found."})

    def do_POST(self) -> None:
        if self.path != "/v1/media/search":
            self.respond(HTTPStatus.NOT_FOUND, {"message": "Not found."})
            return
        token = os.environ.get("VIDEO_DEMO_RUNTIME_TOKEN", "")
        if token and not hmac.compare_digest(self.headers.get("X-Media-Runtime-Token", ""), token):
            self.respond(HTTPStatus.UNAUTHORIZED, {"message": "Unauthorized."})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_REQUEST_BYTES:
                raise ValueError
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(request, dict) or request.get("operation") not in SUPPORTED_OPERATIONS:
                raise ValueError
        except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
            self.respond(HTTPStatus.BAD_REQUEST, {"message": "Invalid media runtime request."})
            return
        self.respond(HTTPStatus.OK, invoke_adapter(request))

    def respond(self, status: HTTPStatus, body: dict[str, Any]) -> None:
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format: str, *_args: Any) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser(description="Agent Studio isolated TVBox media runtime bridge")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18120)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
