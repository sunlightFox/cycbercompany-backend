#!/usr/bin/env python3
"""Focused contract checks for tvbox-runtime-worker.py without any Spider code."""

from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import sys
import unittest

WORKER_PATH = pathlib.Path(__file__).with_name("tvbox-runtime-worker.py")
SPEC = importlib.util.spec_from_file_location("tvbox_runtime_worker", WORKER_PATH)
assert SPEC and SPEC.loader
WORKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WORKER)


class RuntimeWorkerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.previous = os.environ.get("TVBOX_RUNTIME_ADAPTER")

    def tearDown(self) -> None:
        if self.previous is None:
            os.environ.pop("TVBOX_RUNTIME_ADAPTER", None)
        else:
            os.environ["TVBOX_RUNTIME_ADAPTER"] = self.previous

    def test_invokes_reviewed_adapter_with_normalized_json(self) -> None:
        adapter = (
            "import json,sys; request=json.load(sys.stdin); "
            "print(json.dumps({'query':request['query'],'status':'READY','items':[{'id':'demo-1','title':'Demo'}],"
            "'sourceKeys':request['sourceKeys']}))"
        )
        os.environ["TVBOX_RUNTIME_ADAPTER"] = json.dumps([sys.executable, "-c", adapter])

        result = WORKER.invoke_adapter({
            "operation": "media.search", "query": "demo", "sourceKeys": ["source-a"],
        })

        self.assertEqual("READY", result["status"])
        self.assertEqual("demo", result["query"])
        self.assertEqual("demo-1", result["items"][0]["id"])
        self.assertEqual(["source-a"], result["sourceKeys"])

    def test_returns_explicit_status_without_adapter(self) -> None:
        os.environ.pop("TVBOX_RUNTIME_ADAPTER", None)

        result = WORKER.invoke_adapter({"operation": "media.resolvePlayback", "mediaId": "demo-1"})

        self.assertEqual("RUNTIME_UNAVAILABLE", result["status"])
        self.assertEqual("demo-1", result["mediaId"])

    def test_health_requires_a_ready_adapter(self) -> None:
        os.environ.pop("TVBOX_RUNTIME_ADAPTER", None)

        result = WORKER.invoke_adapter({"operation": "runtime.health"})

        self.assertEqual("DOWN", result["status"])
        self.assertFalse(result["ready"])

    def test_invalid_adapter_command_is_reported_without_raising(self) -> None:
        os.environ["TVBOX_RUNTIME_ADAPTER"] = "not-json"

        result = WORKER.invoke_adapter({"operation": "media.search", "query": "demo"})

        self.assertEqual("RUNTIME_UNAVAILABLE", result["status"])
        self.assertIn("invalid JSON", result["message"])


if __name__ == "__main__":
    unittest.main()
