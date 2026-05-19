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

    def test_generator_extracts_copilot_root_events_and_kast_failure_results(self) -> None:
        history_session = SCRATCH_DIR / "copilot-root" / "session-001"
        write_jsonl(
            history_session / "events.jsonl",
            [
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "call-success",
                        "toolName": "kast_callers",
                        "arguments": {
                            "symbol": "renderCapabilities",
                            "containingType": "io.github.amichne.kast.api.docs.DocsDocument",
                            "fileHint": "/workspace/demo/analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
                        },
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-success",
                        "success": True,
                        "result": {
                            "content": json.dumps(
                                {
                                    "type": "CALLERS_SUCCESS",
                                    "ok": True,
                                    "symbol": {
                                        "fqName": "io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities",
                                        "kind": "FUNCTION",
                                        "location": {
                                            "filePath": "/workspace/demo/analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
                                            "startOffset": 0,
                                            "endOffset": 18,
                                            "startLine": 12,
                                            "startColumn": 5,
                                            "preview": "fun renderCapabilities()",
                                        },
                                    },
                                    "filePath": "/workspace/demo/analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
                                    "root": {"symbol": {"fqName": "io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities"}, "children": []},
                                }
                            )
                        },
                    },
                },
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "call-failure",
                        "toolName": "kast_callers",
                        "arguments": {
                            "symbol": "generated capabilities page contains a section for every JSON-RPC method",
                            "fileHint": "/workspace/demo/analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
                        },
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-failure",
                        "success": True,
                        "result": {
                            "content": json.dumps(
                                {
                                    "type": "CALLERS_FAILURE",
                                    "ok": False,
                                    "stage": "resolve",
                                    "message": "No symbol matching the generated test name found in workspace",
                                    "query": {
                                        "symbol": "generated capabilities page contains a section for every JSON-RPC method",
                                        "fileHint": "/workspace/demo/analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
                                    },
                                }
                            )
                        },
                    },
                },
            ],
        )
        catalog = {"skill_name": "demo", "version": 1, "cases": []}
        bindings = {
            "target_repo": "demo",
            "workspace_root": "/workspace/demo",
            "slots": {},
        }

        payload = generate_payload(
            catalog=catalog,
            bindings=bindings,
            history_roots=[SCRATCH_DIR / "copilot-root"],
            generated_at="2026-01-01T00:00:00Z",
        )

        history_callers = [
            entry
            for entry in payload["entries"]
            if entry["method"] == "symbol/callers" and entry["provenance"]["source"] == "history"
        ]
        self.assertEqual(2, len(history_callers))
        self.assertTrue(any(entry["result"]["ok"] is True for entry in history_callers))
        self.assertTrue(any(entry["result"]["ok"] is False for entry in history_callers))
        self.assertEqual(2, payload["provenance_summary"]["history_entry_count"])
        self.assertTrue(all(entry["provenance"]["source_file"].endswith("events.jsonl") for entry in history_callers))

    def test_generator_rejects_kast_binary_resolution_failures_from_history(self) -> None:
        history_run = SCRATCH_DIR / "extension-failure" / "run-1"
        write_jsonl(
            history_run / "sdk-events.jsonl",
            [
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "call-extension-failure",
                        "toolName": "kast_workspace_files",
                        "arguments": {"includeFiles": True},
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-extension-failure",
                        "toolName": "kast_workspace_files",
                        "success": True,
                        "result": {
                            "content": json.dumps(
                                {
                                    "jsonrpc": "2.0",
                                    "id": 1,
                                    "result": {
                                        "ok": False,
                                        "stage": "extension.resolve",
                                        "message": "kast binary not resolved: no resolved Kast CLI supports direct wrapper commands",
                                    },
                                }
                            )
                        },
                    },
                },
            ],
        )
        payload = generate_payload(
            catalog={"skill_name": "demo", "version": 1, "cases": []},
            bindings={
                "target_repo": "demo",
                "workspace_root": "/workspace/demo",
                "slots": {
                    "MODULE_LIST": {
                        "modules": ["demo.main"],
                        "expected": {"moduleFileCounts": {"demo.main": 1}},
                    }
                },
            },
            history_roots=[SCRATCH_DIR / "extension-failure"],
            generated_at="2026-01-01T00:00:00Z",
        )

        self.assertFalse(
            any(
                entry["method"] == "raw/workspace-files"
                and entry["provenance"]["source"] == "history"
                for entry in payload["entries"]
            )
        )
        self.assertTrue(
            any(
                entry["method"] == "raw/workspace-files"
                and entry["provenance"]["fallback"]
                for entry in payload["entries"]
            )
        )
        self.assertEqual(1, payload["provenance_summary"]["rejected_history_entry_count"])

    def test_generator_adds_binding_aliases_for_common_agent_tool_variants(self) -> None:
        catalog = {"skill_name": "demo", "version": 1, "cases": []}
        bindings = {
            "target_repo": "demo",
            "workspace_root": "/workspace/demo",
            "slots": {
                "DISAMBIGUATE_MEMBER": {
                    "symbol": "filePath",
                    "fqName": "io.github.amichne.kast.api.contract.FileOperation.filePath",
                    "file": "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/FileOperation.kt",
                    "containingType": "io.github.amichne.kast.api.contract.FileOperation",
                    "expected": {
                        "expectedFiles": [
                            "analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/EditPlanValidator.kt",
                        ],
                    },
                },
                "OVERLOADED_OR_COMMON_FUNCTION": {
                    "symbol": "renderCapabilities",
                    "fqName": "io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities",
                    "file": "analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
                    "containingType": "io.github.amichne.kast.api.docs.DocsDocument",
                    "expected": {
                        "expectedCallerFqNames": [
                            "io.github.amichne.kast.api.docs.main",
                            "io.github.amichne.kast.api.docs.AnalysisDocsDocumentTest.generated capabilities page contains a section for every JSON-RPC method",
                        ],
                    },
                },
                "RENAME_TARGET": {
                    "symbol": "AnalysisDispatcher",
                    "fqName": "io.github.amichne.kast.server.AnalysisDispatcher",
                    "file": "analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt",
                    "expected": {
                        "affectedFiles": [
                            "analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt",
                            "analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt",
                        ],
                    },
                },
            },
        }

        payload = generate_payload(
            catalog=catalog,
            bindings=bindings,
            history_roots=[],
            generated_at="2026-01-01T00:00:00Z",
        )

        references = [
            entry["matcher"].get("symbol")
            for entry in payload["entries"]
            if entry["method"] == "symbol/references"
        ]
        callers = [
            entry["matcher"].get("symbol")
            for entry in payload["entries"]
            if entry["method"] == "symbol/callers"
        ]
        resolves = [
            entry["matcher"].get("symbol")
            for entry in payload["entries"]
            if entry["method"] == "symbol/resolve"
        ]
        self.assertIn("renderCapabilities", references)
        self.assertIn("io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities", references)
        self.assertIn("io.github.amichne.kast.server.AnalysisDispatcher", references)
        self.assertIn("FileOperation", references)
        self.assertIn("io.github.amichne.kast.api.contract.FileOperation", references)
        self.assertIn("renderCapabilities", callers)
        self.assertIn("io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities", callers)
        self.assertIn("main", callers)
        self.assertIn("io.github.amichne.kast.api.docs.main", callers)
        self.assertIn(
            "generated capabilities page contains a section for every JSON-RPC method",
            callers,
        )
        self.assertIn("main", resolves)
        self.assertIn("io.github.amichne.kast.api.docs.main", resolves)
        self.assertIn(
            "generated capabilities page contains a section for every JSON-RPC method",
            resolves,
        )


if __name__ == "__main__":
    unittest.main()
