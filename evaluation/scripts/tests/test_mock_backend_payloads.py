#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1]
SCRATCH_DIR = Path(__file__).resolve().parent / ".mock-backend-scratch"

if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_mock_backend_payloads import generate_payload


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(row) for row in rows) + "\n")


class MockBackendPayloadTests(unittest.TestCase):
    def tearDown(self) -> None:
        shutil.rmtree(SCRATCH_DIR, ignore_errors=True)

    def test_generator_extracts_history_and_fills_binding_fallbacks(self) -> None:
        history_run = SCRATCH_DIR / "history" / "iteration-001" / "eval-demo" / "with_skill" / "run-1"
        write_jsonl(
            history_run / "sdk-events.jsonl",
            [
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "call-1",
                        "toolName": "kast_resolve",
                        "arguments": {"symbol": "Demo"},
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-1",
                        "toolName": "kast_resolve",
                        "success": True,
                        "result": {
                            "content": json.dumps(
                                {
                                    "jsonrpc": "2.0",
                                    "id": 1,
                                    "result": {
                                        "type": "RESOLVE_SUCCESS",
                                        "ok": True,
                                        "filePath": "/workspace/demo/src/Demo.kt",
                                        "symbol": {
                                            "fqName": "sample.Demo",
                                            "kind": "CLASS",
                                            "location": {
                                                "filePath": "/workspace/demo/src/Demo.kt",
                                                "startOffset": 0,
                                                "endOffset": 4,
                                                "startLine": 1,
                                                "startColumn": 1,
                                                "preview": "class Demo",
                                            },
                                        },
                                    },
                                }
                            )
                        },
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-2",
                        "toolName": "kast_references",
                        "success": False,
                        "result": {"content": "{\"error\":{\"message\":\"boom\"}}"},
                    },
                },
            ],
        )
        catalog = {"skill_name": "demo", "version": 1, "cases": []}
        bindings = {
            "target_repo": "demo",
            "workspace_root": "/workspace/demo",
            "slots": {
                "MODULE_LIST": {
                    "modules": ["demo.main"],
                    "description": "demo module",
                    "expected": {"moduleFileCounts": {"demo.main": 1}},
                },
                "DISAMBIGUATE_MEMBER": {
                    "symbol": "name",
                    "fqName": "sample.Demo.name",
                    "file": "src/Demo.kt",
                    "description": "demo",
                    "containingType": "sample.Demo",
                    "expected": {"expectedFiles": ["src/Use.kt"], "minimumUsageSites": 1},
                },
            },
        }

        payload = generate_payload(
            catalog=catalog,
            bindings=bindings,
            history_roots=[SCRATCH_DIR / "history"],
            generated_at="2026-01-01T00:00:00Z",
        )

        history_resolves = [
            entry
            for entry in payload["entries"]
            if entry["method"] == "symbol/resolve" and entry["provenance"]["source"] == "history"
        ]
        self.assertEqual(1, len(history_resolves))
        self.assertEqual("src/Demo.kt", history_resolves[0]["result"]["filePath"])
        self.assertEqual("src/Demo.kt", history_resolves[0]["result"]["symbol"]["location"]["filePath"])
        self.assertTrue(
            any(
                entry["method"] == "symbol/resolve"
                and entry["matcher"].get("symbol") == "name"
                and entry["provenance"]["source"] == "bindings"
                for entry in payload["entries"]
            )
        )
        self.assertFalse(any(entry["method"] == "symbol/references" and entry["provenance"]["source"] == "history" for entry in payload["entries"]))
        self.assertTrue(any(entry["method"] == "raw/workspace-files" and entry["provenance"]["fallback"] for entry in payload["entries"]))
        self.assertGreater(payload["provenance_summary"]["fallback_entry_count"], 0)


if __name__ == "__main__":
    unittest.main()
