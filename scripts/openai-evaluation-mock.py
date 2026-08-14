#!/usr/bin/env python3
"""Deterministic OpenAI-compatible provider for isolated coding evaluations.

The service is intentionally small and is never a production model substitute. It
returns SSE chat-completion responses, including split function-call payloads, so
the evaluation backend exercises the same streaming assembler and tool loop used
with a real OpenAI-compatible provider.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


def logical_tools(request: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for item in request.get("tools") or []:
        function = item.get("function") or {}
        name = function.get("name")
        description = function.get("description") or ""
        match = re.search(r"Host logical operation: ([a-z0-9._-]+)\.", description)
        if name and match:
            result[match.group(1)] = name
    return result


def log_tool_summary(request: dict[str, Any]) -> None:
    """Log only provider-contract metadata, never prompts or tool arguments."""
    tools = request.get("tools") or []
    names = [str((item.get("function") or {}).get("name") or "<missing>") for item in tools]
    advertised = logical_tools(request)
    operations = sorted(advertised)
    messages = request.get("messages") or []
    completed = called_operations(messages, advertised)
    sys.stderr.write(
        "chat request: stream=%s tools=%d scenario=%s roles=%s completed=%s names=%s logical_operations=%s\n"
        % (bool(request.get("stream")), len(tools), scenario(messages), ",".join(str(message.get("role")) for message in messages), ",".join(completed), ",".join(names), ",".join(operations))
    )


def tool_summary(request: dict[str, Any]) -> dict[str, Any]:
    tools = request.get("tools") or []
    return {
        "stream": bool(request.get("stream")),
        "toolCount": len(tools),
        "functionNames": [str((item.get("function") or {}).get("name") or "<missing>") for item in tools],
        "logicalOperations": sorted(logical_tools(request)),
    }


def scenario(messages: list[dict[str, Any]]) -> str:
    text = "\n".join(str(message.get("content") or "") for message in messages)
    folded = text.casefold()
    if "taxcalculator" in folded:
        return "minimal-fix"
    if "taskrepository" in folded:
        return "existing-feature"
    if "long task recovery" in folded or "recovered workflow" in folded:
        return "recovery"
    if (
        "split frontend/backend" in folded
        or "split-frontend-backend" in folded
        or ("frontend" in folded and "backend" in folded)
        or ("displayname" in folded and "role" in folded)
    ):
        return "split"
    return "full-stack"


def called_operations(messages: list[dict[str, Any]], advertised: dict[str, str]) -> list[str]:
    by_model_name = {model_name: logical for logical, model_name in advertised.items()}
    calls: list[str] = []
    for message in messages:
        for call in message.get("tool_calls") or []:
            name = ((call.get("function") or {}).get("name"))
            if name:
                calls.append(by_model_name.get(name, name))
    return calls


def process_id(messages: list[dict[str, Any]]) -> str | None:
    for message in reversed(messages):
        if message.get("role") != "tool":
            continue
        content = str(message.get("content") or "")
        match = re.search(r'"processId"\s*:\s*"([^"]+)"', content)
        if match:
            return match.group(1)
    return None


def next_operation(kind: str, calls: list[str]) -> tuple[str | None, dict[str, Any]]:
    # The helper returns logical names. They are resolved against the advertised
    # functions per request so each run remains bound to the server snapshot.
    sequences: dict[str, list[tuple[str, dict[str, Any]]]] = {
        "minimal-fix": [
            ("fs.read", {"path": "README.md"}),
            ("shell.run", {"command": "javac TaxCalculator.java TaxCalculatorTest.java && java TaxCalculatorTest"}),
            ("fs.read", {"path": "TaxCalculator.java"}),
            ("fs.apply_patch", {"path": "TaxCalculator.java", "expected": "amount / 100", "replacement": "amount / 10"}),
            ("shell.run", {"command": "javac TaxCalculator.java TaxCalculatorTest.java && java TaxCalculatorTest"}),
            ("git.review", {}),
            ("git.diff", {}),
        ],
        "existing-feature": [
            ("fs.read", {"path": "README.md"}),
            ("shell.run", {"command": "javac TaskRepository.java TaskRepositoryTest.java && java TaskRepositoryTest"}),
            ("fs.read", {"path": "TaskRepository.java"}),
            ("fs.apply_patch", {"path": "TaskRepository.java", "expected": "    public List<Task> all() { return tasks; }", "replacement": "    public List<Task> all() { return tasks; }\n    public List<Task> completed() { return tasks.stream().filter(Task::completed).toList(); }"}),
            ("fs.write", {"path": "TaskRepositoryCompletedTest.java", "content": "public final class TaskRepositoryCompletedTest {\n    public static void main(String[] args) {\n        if (new TaskRepository().completed().size() != 1) throw new AssertionError(\"one completed task expected\");\n        System.out.println(\"TaskRepositoryCompletedTest passed\");\n    }\n}\n"}),
            ("shell.run", {"command": "javac TaskRepository.java TaskRepositoryTest.java TaskRepositoryCompletedTest.java && java TaskRepositoryTest && java TaskRepositoryCompletedTest"}),
            ("git.review", {}),
            ("git.diff", {}),
        ],
        "recovery": [
            ("fs.read", {"path": "SCENARIO.md"}),
            ("fs.write", {"path": "App.java", "content": "public final class App {\n    public static void main(String[] args) {\n        System.out.println(\"Recovered workflow complete\");\n    }\n}\n"}),
            ("fs.write", {"path": "AppTest.java", "content": "public final class AppTest {\n    public static void main(String[] args) {\n        App.main(new String[0]);\n        System.out.println(\"AppTest passed\");\n    }\n}\n"}),
            ("shell.run", {"command": "javac App.java AppTest.java && java AppTest"}),
            ("git.review", {}),
            ("git.diff", {}),
        ],
        "split": [
            ("project.map", {"cwd": "."}),
            ("fs.read", {"path": "SCENARIO.md"}),
            ("fs.write", {"path": "backend/server.py", "content": "from http.server import BaseHTTPRequestHandler, HTTPServer\nfrom pathlib import Path\nimport json\n\nROOT = Path(__file__).resolve().parent.parent\n\nclass App(BaseHTTPRequestHandler):\n    def do_GET(self):\n        if self.path == '/api/profile':\n            body = json.dumps({'displayName': 'Ada', 'role': 'Engineer'}).encode()\n            self.send_response(200)\n            self.send_header('Content-Type', 'application/json')\n            self.end_headers()\n            self.wfile.write(body)\n            return\n        body = (ROOT / 'frontend' / 'index.html').read_bytes()\n        self.send_response(200)\n        self.send_header('Content-Type', 'text/html; charset=utf-8')\n        self.end_headers()\n        self.wfile.write(body)\n\nHTTPServer(('127.0.0.1', 18092), App).serve_forever()\n"}),
            ("fs.write", {"path": "frontend/index.html", "content": "<!doctype html>\n<html><body><button id=\"load\" onclick=\"loadProfile()\">Load profile</button><p id=\"result\">Ready</p><script>async function loadProfile(){const response=await fetch('/api/profile');const profile=await response.json();document.getElementById('result').textContent=profile.displayName+' '+profile.role;}</script></body></html>\n"}),
            ("fs.write", {"path": "package.json", "content": "{\n  \"scripts\": {\n    \"build\": \"py -3 -m py_compile backend/server.py\",\n    \"test\": \"py -3 -m unittest backend/test_profile.py\"\n  }\n}\n"}),
            ("fs.write", {"path": "backend/test_profile.py", "content": "from pathlib import Path\nimport unittest\n\nROOT = Path(__file__).resolve().parent.parent\n\nclass ProfileContractTest(unittest.TestCase):\n    def test_profile_contract_is_present(self):\n        self.assertIn(\"/api/profile\", (ROOT / \"backend\" / \"server.py\").read_text())\n        page = (ROOT / \"frontend\" / \"index.html\").read_text()\n        self.assertIn(\"displayName\", page)\n        self.assertIn(\"role\", page)\n\nif __name__ == \"__main__\":\n    unittest.main()\n"}),
            ("shell.run", {"command": "npm run build"}),
            ("shell.run", {"command": "npm test"}),
            ("process.start", {"command": "py -3 backend/server.py"}),
        ],
    }
    sequence = sequences.get(kind)
    if sequence is None:
        # Full-stack fixtures deliberately start empty. The mock writes a small
        # local HTTP application whose page performs an API request, which lets
        # the evaluation exercise process readiness plus browser/API evidence.
        server = """from http.server import BaseHTTPRequestHandler, HTTPServer\nimport json\nclass App(BaseHTTPRequestHandler):\n    def do_GET(self):\n        if self.path == '/api/profile':\n            body = json.dumps({'displayName':'Ada','role':'Engineer'}).encode()\n            self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(body); return\n        self.send_response(200); self.send_header('Content-Type','text/html'); self.end_headers(); self.wfile.write(b\"<button id='load' onclick='load()'>Load profile</button><p id='result'>Ready</p><script>async function load(){let r=await fetch('/api/profile');let p=await r.json();document.getElementById('result').textContent=p.displayName+' '+p.role}</script>\")\nHTTPServer(('127.0.0.1', 18091), App).serve_forever()\n"""
        sequence = [
            ("project.inspect", {"path": "."}),
            ("fs.read", {"path": "SCENARIO.md"}),
            ("fs.write", {"path": "server.py", "content": server}),
            ("fs.write", {"path": "README.md", "content": "Evaluation full-stack implementation: local profile API and browser client.\n"}),
            ("shell.run", {"command": "py -3 -m py_compile server.py && echo build passed"}),
            ("process.start", {"command": "py -3 server.py"}),
        ]
    # process.wait_http and browser steps are filled below because the opaque
    # processId only appears in the preceding tool result.
    if kind in {"full-stack", "split"} and any(
        name.endswith("process_start") or name == "process.start" for name in calls
    ):
        port = 18092 if kind == "split" else 18091
        url = f"http://127.0.0.1:{port}/"
        sequence.extend([
            ("process.wait_http", {"processId": "__PROCESS_ID__", "url": url, "expectedStatus": 200}),
            ("browser.trace.start", {}),
            ("browser.open", {"url": url}),
            ("browser.click", {"selector": "#load"}),
            ("browser.verify", {"checks": [{"type": "textContains", "value": "Ada Engineer"}, {"type": "responseStatus", "value": 200, "urlContains": "/api/profile"}]}),
            ("browser.trace.stop", {}),
            ("git.review", {}),
            ("git.diff", {}),
        ])
    # Calls such as shell.run and fs.read intentionally occur more than once:
    # first to reproduce/inspect, then to verify. Advance by the completed
    # sequence position instead of treating an operation name as a set.
    if len(calls) < len(sequence):
        operation, arguments = sequence[len(calls)]
        return operation, arguments
    return None, {}


def plan(request: dict[str, Any]) -> tuple[str | None, dict[str, Any], str]:
    messages = request.get("messages") or []
    if not logical_tools(request):
        return None, {}, "Evaluation mock connectivity probe succeeded."
    kind = scenario(messages)
    operation, arguments = next_operation(kind, called_operations(messages, logical_tools(request)))
    if arguments.get("processId") == "__PROCESS_ID__":
        value = process_id(messages)
        if not value:
            return None, {}, "The managed process handle was not returned; stop and report the blocker."
        arguments = dict(arguments)
        arguments["processId"] = value
    return operation, arguments, "Evaluation workflow completed with inspected files, verified commands, and a final review."


class Handler(BaseHTTPRequestHandler):
    server_version = "CycberCompanyEvaluationMock/1.0"

    def log_message(self, format: str, *args: object) -> None:
        sys.stderr.write((format % args) + "\n")

    def do_GET(self) -> None:
        if self.path.rstrip("/") == "/healthz":
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            payload = {"status": "UP", "lastToolSummary": getattr(self.server, "last_tool_summary", None)}
            self.wfile.write(json.dumps(payload, ensure_ascii=True).encode("utf-8"))
            return
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        try:
            raw = self.request_body()
            request = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            # This is deliberately byte-count-only diagnostics. The evaluator
            # may send source code in prompts, which must never be logged.
            sys.stderr.write(f"invalid JSON: content-length={self.headers.get('Content-Length')} bytes={len(locals().get('raw', b''))}\n")
            self.send_error(HTTPStatus.BAD_REQUEST, "invalid JSON")
            return
        log_tool_summary(request)
        self.server.last_tool_summary = tool_summary(request)
        operation, arguments, final_text = plan(request)
        tool_name = logical_tools(request).get(operation or "")
        if operation and not tool_name:
            self.send_error(HTTPStatus.BAD_REQUEST, "required evaluation tool is unavailable")
            return
        if request.get("stream"):
            self.stream(tool_name, arguments, final_text)
        else:
            self.complete(tool_name, arguments, final_text)

    def request_body(self) -> bytes:
        """Read both fixed-length and JDK HttpClient's chunked request bodies."""
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            return self.rfile.read(int(self.headers.get("Content-Length", "0")))
        chunks: list[bytes] = []
        while True:
            line = self.rfile.readline()
            if not line:
                raise ValueError("unexpected EOF in chunked request")
            size = int(line.split(b";", 1)[0].strip(), 16)
            if size == 0:
                self.rfile.readline()
                return b"".join(chunks)
            chunks.append(self.rfile.read(size))
            if self.rfile.read(2) != b"\r\n":
                raise ValueError("malformed chunked request")

    def complete(self, tool_name: str | None, arguments: dict[str, Any], final_text: str) -> None:
        message: dict[str, Any] = {"role": "assistant", "content": "" if tool_name else final_text}
        finish_reason = "stop"
        if tool_name:
            message["tool_calls"] = [{"id": "mock_call", "type": "function", "function": {"name": tool_name, "arguments": json.dumps(arguments, ensure_ascii=True)}}]
            finish_reason = "tool_calls"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers()
        self.wfile.write(json.dumps({"id": "mock", "model": "evaluation-mock", "choices": [{"message": message, "finish_reason": finish_reason}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}).encode("utf-8"))

    def stream(self, tool_name: str | None, arguments: dict[str, Any], final_text: str) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        def event(payload: dict[str, Any]) -> None:
            self.wfile.write(("data: " + json.dumps(payload, ensure_ascii=True) + "\n\n").encode("utf-8"))
            self.wfile.flush()
        if tool_name:
            raw = json.dumps(arguments, ensure_ascii=True, separators=(",", ":"))
            midpoint = max(1, len(raw) // 2)
            event({"model": "evaluation-mock", "choices": [{"delta": {"tool_calls": [{"index": 0, "id": "mock_call", "type": "function", "function": {"name": tool_name, "arguments": raw[:midpoint]}}]}, "finish_reason": None}]})
            event({"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": raw[midpoint:]}}]}, "finish_reason": "tool_calls"}]})
        else:
            event({"model": "evaluation-mock", "choices": [{"delta": {"content": final_text}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 1, "completion_tokens": 1}})
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"OpenAI evaluation mock listening on http://{args.host}:{args.port}/v1", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
