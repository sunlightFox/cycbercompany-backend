#!/usr/bin/env python3
"""End-to-end bridge test with a fake engine; never executes Spider code."""

from __future__ import annotations

import http.server
import importlib.util
import json
import os
import pathlib
import socket
import subprocess
import threading
import time
import urllib.request
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
ADAPTER = ROOT / "scripts" / "tvbox-compatible-adapter.py"
WORKER = ROOT / "scripts" / "tvbox-runtime-worker.py"
CONFIG = ROOT / "output" / "tvbox-config.json"


class EngineHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length).decode("utf-8"))
        operation = request.get("operation")
        if operation == "runtime.health":
            response = {"status": "UP", "ready": True}
        elif operation == "media.search":
            response = {"status": "READY", "query": request.get("query"), "sourceKeys": ["视界"],
                        "items": [{"id": "jasmine-1", "title": "机器猫", "sourceKey": "视界",
                                   "sourceName": "茉莉多线"}]}
        else:
            response = {"status": "READY", "mediaId": request.get("mediaId"), "sourceId": "视界",
                        "episodeId": request.get("episodeId"), "streamUrl": "https://media.example.invalid/1.m3u8",
                        "mimeType": "application/vnd.apple.mpegurl", "durationMs": 1000,
                        "subtitleUrls": [], "requestHeaders": {"Referer": "https://media.example.invalid/"}}
        body = json.dumps(response, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args) -> None:
        return


class EngineProtocolTest(unittest.TestCase):
    def test_worker_adapter_engine_contract(self) -> None:
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), EngineHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        environment = os.environ.copy()
        environment.update({
            "TVBOX_RUNTIME_ADAPTER": json.dumps(["py", "-3.12", str(ADAPTER)]),
            "VIDEO_DEMO_TVBOX_CONFIG": str(CONFIG),
            "VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT": f"http://127.0.0.1:{server.server_port}/execute",
        })
        port_socket = socket.socket()
        port_socket.bind(("127.0.0.1", 0))
        worker_port = port_socket.getsockname()[1]
        port_socket.close()
        process = subprocess.Popen(["py", "-3.12", str(WORKER), "--port", str(worker_port)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=environment)
        try:
            health_url = f"http://127.0.0.1:{worker_port}/health"
            deadline = time.time() + 5
            while True:
                try:
                    with urllib.request.urlopen(health_url, timeout=1) as response:
                        health = json.loads(response.read().decode("utf-8"))
                    break
                except OSError:
                    if time.time() >= deadline:
                        raise
                    time.sleep(0.05)
            self.assertTrue(health["adapterReady"])
            request = {"operation": "media.resolvePlayback", "mediaId": "jasmine-1",
                       "sourceId": "视界", "episodeId": "1", "sourceKeys": ["视界"]}
            body = json.dumps(request, ensure_ascii=False).encode("utf-8")
            http_request = urllib.request.Request(f"http://127.0.0.1:{worker_port}/v1/media/search",
                                                  data=body, method="POST",
                                                  headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(http_request, timeout=5) as response:
                result = json.loads(response.read().decode("utf-8"))
            self.assertEqual("READY", result["status"], result)
            self.assertEqual("https://media.example.invalid/1.m3u8", result["streamUrl"])
            self.assertEqual("https://media.example.invalid/", result["requestHeaders"]["Referer"])
        finally:
            server.shutdown()
            server.server_close()
            process.terminate()
            process.wait(timeout=5)
            if process.stdout:
                process.stdout.close()
            if process.stderr:
                process.stderr.close()


if __name__ == "__main__":
    unittest.main()
