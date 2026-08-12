#!/usr/bin/env python3
"""Video Demo adapter for protected TVBox csp_* sources.

This process is intentionally only a protocol bridge. It does not import a
DEX, load JNI, download a Spider, or execute JavaScript. An Android/TVBox
compatible engine must be supplied separately through
VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT. The engine receives a source descriptor and
returns the normalized Video Mod response.

The script is suitable for TVBOX_RUNTIME_ADAPTER, for example:
  ["python", "scripts/tvbox-compatible-adapter.py"]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
from typing import Any

MAX_CONFIG_BYTES = 4 * 1024 * 1024
MAX_ENGINE_RESPONSE_BYTES = 2 * 1024 * 1024
DEFAULT_CONFIG = pathlib.Path("output/tvbox-config.json")
DEFAULT_ENGINE_TIMEOUT = 45
SUPPORTED_OPERATIONS = {"media.search", "media.resolvePlayback"}
HEALTH_OPERATION = "runtime.health"


def load_config(path: pathlib.Path) -> tuple[dict[str, Any], str]:
    raw = path.read_bytes()
    if len(raw) > MAX_CONFIG_BYTES:
        raise ValueError("TVBox config is too large.")
    # TVBox configs are commonly exported with a UTF-8 BOM.
    config = json.loads(raw.decode("utf-8-sig"))
    if not isinstance(config, dict) or not isinstance(config.get("sites"), list):
        raise ValueError("TVBox config must contain a sites array.")
    digest = "sha256:" + hashlib.sha256(raw).hexdigest()
    return config, digest


def source_map(config: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for raw in config.get("sites", []):
        if not isinstance(raw, dict):
            continue
        key = str(raw.get("key") or "").strip()
        api = str(raw.get("api") or "").strip()
        if key and api:
            result[key] = {
                "key": key,
                "name": str(raw.get("name") or key),
                "api": api,
                "ext": raw.get("ext"),
                "timeout": raw.get("timeout"),
                "searchable": raw.get("searchable", 0),
                "quickSearch": raw.get("quickSearch", 0),
                "changeable": raw.get("changeable", 0),
            }
    return result


def unavailable(request: dict[str, Any], status: str, message: str) -> dict[str, Any]:
    if request.get("operation") == "media.search":
        return {
            "query": str(request.get("query") or ""),
            "status": status,
            "message": message,
            "items": [],
            "sourceKeys": list(request.get("sourceKeys") or []),
        }
    return {
        "status": status,
        "mediaId": str(request.get("mediaId") or ""),
        "sourceId": request.get("sourceId"),
        "episodeId": request.get("episodeId"),
        "streamUrl": None,
        "mimeType": None,
        "durationMs": 0,
        "subtitleUrls": [],
        "message": message,
    }


def engine_endpoint() -> str:
    return os.environ.get("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT", "").strip()


def build_engine_request(request: dict[str, Any], config: dict[str, Any], digest: str) -> dict[str, Any]:
    sources = source_map(config)
    requested = request.get("sourceKeys") or []
    if request.get("operation") == "media.resolvePlayback":
        requested = [request.get("sourceId")]
    selected: list[dict[str, Any]] = []
    for key in requested:
        source = sources.get(str(key))
        if source is not None and source["api"].startswith("csp_"):
            selected.append(source)
    if not selected:
        raise ValueError("No csp_* source was selected.")
    return {
        "protocol": "agentstudio.tvbox.engine.v1",
        "operation": request.get("operation"),
        "query": request.get("query"),
        "mediaId": request.get("mediaId"),
        "sourceId": request.get("sourceId"),
        "episodeId": request.get("episodeId"),
        "sourceUrl": request.get("sourceUrl"),
        "configDigest": request.get("configDigest") or digest,
        "spiderRef": config.get("spider"),
        "sources": selected,
    }


def call_engine(payload: dict[str, Any], timeout: int = DEFAULT_ENGINE_TIMEOUT) -> dict[str, Any]:
    endpoint = engine_endpoint()
    if not endpoint:
        raise RuntimeError("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT is not configured.")
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(endpoint, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json",
        "User-Agent": "AgentStudio-VideoDemo-TVBoxAdapter/1.0",
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(MAX_ENGINE_RESPONSE_BYTES + 1)
    except (OSError, urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError(f"TVBox engine unavailable: {exc.__class__.__name__}") from exc
    if len(raw) > MAX_ENGINE_RESPONSE_BYTES:
        raise RuntimeError("TVBox engine response is too large.")
    result = json.loads(raw.decode("utf-8"))
    if not isinstance(result, dict):
        raise RuntimeError("TVBox engine returned an invalid JSON object.")
    return result


def adapt(request: dict[str, Any], config_path: pathlib.Path) -> dict[str, Any]:
    operation = request.get("operation")
    if operation == HEALTH_OPERATION:
        try:
            config, digest = load_config(config_path)
            compatible = [source for source in source_map(config).values() if source["api"].startswith("csp_")]
            if not engine_endpoint():
                return {"status": "DOWN", "ready": False,
                        "message": "Video Demo TVBox engine endpoint is not configured."}
            engine = call_engine({
                "protocol": "agentstudio.tvbox.engine.v1",
                "operation": HEALTH_OPERATION,
                "configDigest": digest,
                "sources": compatible,
            }, timeout=3)
            ready = bool(engine.get("ready"))
            return {"status": "UP" if ready else "DOWN", "ready": ready,
                    "message": str(engine.get("message") or ("" if ready else "TVBox engine is not ready."))}
        except (FileNotFoundError, ValueError, json.JSONDecodeError) as exc:
            return {"status": "DOWN", "ready": False, "message": str(exc)}
        except RuntimeError as exc:
            return {"status": "DOWN", "ready": False, "message": str(exc)}
    if operation not in SUPPORTED_OPERATIONS:
        return unavailable(request, "RUNTIME_UNAVAILABLE", "Unsupported media operation.")
    try:
        config, digest = load_config(config_path)
        payload = build_engine_request(request, config, digest)
        response = call_engine(payload)
        # The outer worker performs final shape and header filtering. Keep the
        # engine result domain-shaped so new source metadata can evolve there.
        return response
    except FileNotFoundError:
        return unavailable(request, "RUNTIME_UNAVAILABLE", "Video Demo TVBox config was not found.")
    except json.JSONDecodeError:
        return unavailable(request, "RUNTIME_UNAVAILABLE", "Video Demo TVBox config or engine response is invalid JSON.")
    except ValueError as exc:
        return unavailable(request, "RUNTIME_REQUIRED", str(exc))
    except RuntimeError as exc:
        return unavailable(request, "RUNTIME_REQUIRED", str(exc))


def main() -> None:
    parser = argparse.ArgumentParser(description="Video Demo protected TVBox source adapter")
    parser.add_argument("--config", default=os.environ.get("VIDEO_DEMO_TVBOX_CONFIG", str(DEFAULT_CONFIG)))
    args = parser.parse_args()
    config_path = pathlib.Path(args.config)
    # The parent Worker sends UTF-8 bytes. Do not let Windows' active code page
    # turn Chinese source keys such as 视界 into replacement characters.
    for raw_line in sys.stdin.buffer:
        try:
            request = json.loads(raw_line.decode("utf-8"))
            if not isinstance(request, dict):
                raise ValueError("Request must be a JSON object.")
            response = adapt(request, config_path)
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as exc:
            response = {"status": "RUNTIME_UNAVAILABLE", "message": str(exc), "items": []}
        payload = (json.dumps(response, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        sys.stdout.buffer.write(payload)
        sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
