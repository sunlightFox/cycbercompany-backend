#!/usr/bin/env python3
"""Contract tests for the protected-source bridge; no Spider code is loaded."""

from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import tempfile
import unittest
import subprocess
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "tvbox-compatible-adapter.py"
SPEC = importlib.util.spec_from_file_location("video_demo_tvbox_adapter", MODULE_PATH)
assert SPEC and SPEC.loader
ADAPTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ADAPTER)


class AdapterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.previous = os.environ.get("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT")

    def tearDown(self) -> None:
        if self.previous is None:
            os.environ.pop("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT", None)
        else:
            os.environ["VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT"] = self.previous

    def config(self) -> pathlib.Path:
        handle = tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False)
        self.addCleanup(lambda: pathlib.Path(handle.name).unlink(missing_ok=True))
        json.dump({"spider": "https://example.invalid/spider.bin", "sites": [
            {"key": "视界", "name": "茉莉多线", "api": "csp_App99Guard", "ext": "encrypted"},
            {"key": "web", "name": "Web", "api": "http://example.invalid/api"},
        ]}, handle, ensure_ascii=False)
        handle.close()
        return pathlib.Path(handle.name)

    def test_requires_external_engine_for_csp_source(self) -> None:
        os.environ.pop("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT", None)
        result = ADAPTER.adapt({"operation": "media.search", "query": "机器猫", "sourceKeys": ["视界"]}, self.config())
        self.assertEqual("RUNTIME_REQUIRED", result["status"])
        self.assertIn("ENGINE_ENDPOINT", result["message"])

    def test_health_is_down_until_engine_endpoint_is_configured(self) -> None:
        os.environ.pop("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT", None)

        result = ADAPTER.adapt({"operation": "runtime.health"}, self.config())

        self.assertEqual("DOWN", result["status"])
        self.assertFalse(result["ready"])

    def test_sends_only_selected_csp_source_to_engine(self) -> None:
        os.environ["VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT"] = "http://127.0.0.1:18220/v1/execute"
        response = {"status": "READY", "items": [{"id": "demo-1", "title": "Demo"}], "sourceKeys": ["视界"]}

        class FakeResponse:
            def __enter__(self): return self
            def __exit__(self, *_args): return False
            def read(self, _limit): return json.dumps(response).encode()

        with patch.object(ADAPTER.urllib.request, "urlopen", return_value=FakeResponse()) as mocked:
            result = ADAPTER.adapt({"operation": "media.search", "query": "机器猫", "sourceKeys": ["视界", "web"]}, self.config())
        self.assertEqual("READY", result["status"])
        payload = json.loads(mocked.call_args.args[0].data.decode())
        self.assertEqual(["csp_App99Guard"], [source["api"] for source in payload["sources"]])
        self.assertEqual("视界", payload["sources"][0]["key"])

    def test_accepts_tvbox_config_with_utf8_bom(self) -> None:
        content = json.dumps({"sites": [{"key": "视界", "api": "csp_App99Guard"}]}, ensure_ascii=False)
        handle = tempfile.NamedTemporaryFile("wb", suffix=".json", delete=False)
        self.addCleanup(lambda: pathlib.Path(handle.name).unlink(missing_ok=True))
        handle.write(content.encode("utf-8-sig"))
        handle.close()
        os.environ.pop("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT", None)

        result = ADAPTER.adapt({"operation": "runtime.health"}, pathlib.Path(handle.name))

        self.assertEqual("DOWN", result["status"])
        self.assertIn("engine endpoint", result["message"])

    def test_health_probes_engine_readiness(self) -> None:
        os.environ["VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT"] = "http://127.0.0.1:18220/v1/execute"

        with patch.object(ADAPTER, "call_engine", return_value={"status": "UP", "ready": True}):
            result = ADAPTER.adapt({"operation": "runtime.health"}, self.config())

        self.assertEqual("UP", result["status"])
        self.assertTrue(result["ready"])

    def test_stdio_preserves_utf8_source_keys(self) -> None:
        os.environ.pop("VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT", None)
        request = json.dumps({"operation": "media.resolvePlayback", "sourceId": "视界", "mediaId": "x"},
                             ensure_ascii=False).encode("utf-8")
        result = subprocess.run(
            ["py", "-3.12", str(MODULE_PATH)], input=request + b"\n",
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
        )
        decoded = json.loads(result.stdout.decode("utf-8"))
        self.assertEqual("RUNTIME_REQUIRED", decoded["status"])
        self.assertNotIn("No csp_* source", decoded["message"])


if __name__ == "__main__":
    unittest.main()
