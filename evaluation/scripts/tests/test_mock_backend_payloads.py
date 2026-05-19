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
                        "arguments": {"symbol": "OtherDemo"},
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
                                        "filePath": "/workspace/demo/src/OtherDemo.kt",
                                        "symbol": {
                                            "fqName": "sample.OtherDemo",
                                            "kind": "CLASS",
                                            "location": {
                                                "filePath": "/workspace/demo/src/OtherDemo.kt",
                                                "startOffset": 0,
                                                "endOffset": 4,
                                                "startLine": 1,
                                                "startColumn": 1,
                                                "preview": "class OtherDemo",
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
        self.assertEqual("src/OtherDemo.kt", history_resolves[0]["result"]["filePath"])
        self.assertEqual("src/OtherDemo.kt", history_resolves[0]["result"]["symbol"]["location"]["filePath"])
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
        failure_stage = "extension" + ".resolve"
        failure_message = "kast binary not " + "resolved: no resolved Kast CLI supports direct wrapper commands"
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
                                        "stage": failure_stage,
                                        "message": failure_message,
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

    def test_generator_rejects_wrapped_or_stale_benchmark_worktree_history(self) -> None:
        history_run = SCRATCH_DIR / "stale-history" / "run-1"
        write_jsonl(
            history_run / "sdk-events.jsonl",
            [
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "call-wrapped",
                        "toolName": "kast_references",
                        "arguments": {"symbol": "AnalysisBackend"},
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-wrapped",
                        "toolName": "kast_references",
                        "success": True,
                        "result": {
                            "content": json.dumps(
                                {
                                    "jsonrpc": "2.0",
                                    "id": 1,
                                    "result": {
                                        "content": "Output too large to read at once. Saved to: /tmp/copilot-tool-output.txt",
                                        "detailedContent": "not-json payload copied from a previous benchmark worktree",
                                    },
                                }
                            )
                        },
                    },
                },
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "call-stale-path",
                        "toolName": "kast_references",
                        "arguments": {"symbol": "io.github.amichne.kast.api.contract.AnalysisBackend"},
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-stale-path",
                        "toolName": "kast_references",
                        "success": True,
                        "result": {
                            "content": json.dumps(
                                {
                                    "jsonrpc": "2.0",
                                    "id": 1,
                                    "result": {
                                        "type": "REFERENCES_SUCCESS",
                                        "ok": True,
                                        "references": [
                                            {
                                                "filePath": "/workspace/demo/.benchmarks/copilot-sdk-mock/old/eval-demo/tool_only/run-1/worktree/analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt",
                                                "startLine": 1,
                                            }
                                        ],
                                    },
                                }
                            )
                        },
                    },
                },
                {
                    "type": "tool.execution_start",
                    "data": {
                        "toolCallId": "call-clean-failure",
                        "toolName": "kast_references",
                        "arguments": {"symbol": "io.github.amichne.kast.api.contract.AnalysisBackend"},
                    },
                },
                {
                    "type": "tool.execution_complete",
                    "data": {
                        "toolCallId": "call-clean-failure",
                        "toolName": "kast_references",
                        "success": True,
                        "result": {
                            "content": json.dumps(
                                {
                                    "jsonrpc": "2.0",
                                    "id": 1,
                                    "result": {
                                        "type": "REFERENCES_FAILURE",
                                        "ok": False,
                                        "stage": "resolve",
                                        "message": "No symbol matching AnalysisBackend found",
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
                    "CROSS_MODULE_CLASS": {
                        "symbol": "AnalysisBackend",
                        "fqName": "io.github.amichne.kast.api.contract.AnalysisBackend",
                        "file": "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/AnalysisBackend.kt",
                        "expected": {
                            "expectedConsumerFiles": [
                                "analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt",
                            ],
                        },
                    }
                },
            },
            history_roots=[SCRATCH_DIR / "stale-history"],
            generated_at="2026-01-01T00:00:00Z",
        )

        self.assertEqual(2, payload["provenance_summary"]["rejected_history_entry_count"])
        references = [
            entry
            for entry in payload["entries"]
            if entry["method"] == "symbol/references"
            and entry["matcher"] == {"symbol": "AnalysisBackend"}
        ]
        self.assertEqual(1, len(references))
        self.assertEqual("bindings", references[0]["provenance"]["source"])
        self.assertEqual(
            ["analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt"],
            [ref["filePath"] for ref in references[0]["result"]["references"]],
        )
        fq_references = [
            entry
            for entry in payload["entries"]
            if entry["method"] == "symbol/references"
            and entry["matcher"] == {"symbol": "io.github.amichne.kast.api.contract.AnalysisBackend"}
        ]
        self.assertEqual(1, len(fq_references))
        self.assertEqual("bindings", fq_references[0]["provenance"]["source"])
        self.assertTrue(fq_references[0]["result"]["ok"])

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
                "CROSS_MODULE_CLASS": {
                    "symbol": "AnalysisBackend",
                    "fqName": "io.github.amichne.kast.api.contract.AnalysisBackend",
                    "file": "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/AnalysisBackend.kt",
                    "expected": {
                        "minimumReferences": 3,
                        "expectedConsumerFiles": [
                            "analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt",
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
                            "io.github.amichne.kast.api.docs.AnalysisDocsDocumentTest.checked in capabilities markdown matches generated document",
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
            entry["matcher"]
            for entry in payload["entries"]
            if entry["method"] == "symbol/references"
        ]
        callers = [
            entry["matcher"]
            for entry in payload["entries"]
            if entry["method"] == "symbol/callers"
        ]
        resolves = [
            entry["matcher"].get("symbol")
            for entry in payload["entries"]
            if entry["method"] == "symbol/resolve"
        ]
        reference_symbols = [matcher.get("symbol") for matcher in references]
        caller_symbols = [matcher.get("symbol") for matcher in callers]
        self.assertIn("renderCapabilities", reference_symbols)
        self.assertIn("io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities", reference_symbols)
        self.assertIn("io.github.amichne.kast.server.AnalysisDispatcher", reference_symbols)
        self.assertIn("FileOperation", reference_symbols)
        self.assertIn("io.github.amichne.kast.api.contract.FileOperation", reference_symbols)
        self.assertIn("main", reference_symbols)
        self.assertIn("io.github.amichne.kast.api.docs.main", reference_symbols)
        self.assertIn(
            "generated capabilities page contains a section for every JSON-RPC method",
            reference_symbols,
        )
        self.assertIn(
            "io.github.amichne.kast.api.docs.AnalysisDocsDocumentTest.`generated capabilities page contains a section for every JSON-RPC method`",
            reference_symbols,
        )
        self.assertIn("generated", reference_symbols)
        self.assertIn("markdown", reference_symbols)
        self.assertIn(
            {
                "symbol": "AnalysisBackend",
                "kind": "INTERFACE",
                "fileHint": "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/AnalysisBackend.kt",
            },
            references,
        )
        analysis_backend_reference = next(
            entry
            for entry in payload["entries"]
            if entry["method"] == "symbol/references" and entry["matcher"] == {"symbol": "AnalysisBackend"}
        )
        self.assertEqual(3, len(analysis_backend_reference["result"]["references"]))
        self.assertEqual(
            [1, 2, 3],
            [reference["startLine"] for reference in analysis_backend_reference["result"]["references"]],
        )
        self.assertIn(
            {
                "symbol": "filePath",
                "kind": "PROPERTY",
                "containingType": "io.github.amichne.kast.api.contract.FileOperation",
                "fileHint": "analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/FileOperation.kt",
            },
            references,
        )
        render_capabilities_reference = next(
            entry
            for entry in payload["entries"]
            if entry["method"] == "symbol/references" and entry["matcher"] == {"symbol": "renderCapabilities"}
        )
        self.assertEqual(3, len(render_capabilities_reference["result"]["references"]))
        self.assertEqual(
            [
                "analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
                "analysis-api/src/test/kotlin/io/github/amichne/kast/api/docs/AnalysisDocsDocumentTest.kt",
                "analysis-api/src/test/kotlin/io/github/amichne/kast/api/docs/AnalysisDocsDocumentTest.kt",
            ],
            [reference["filePath"] for reference in render_capabilities_reference["result"]["references"]],
        )
        self.assertIn("renderCapabilities", caller_symbols)
        self.assertIn("io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities", caller_symbols)
        self.assertIn("main", caller_symbols)
        self.assertIn("io.github.amichne.kast.api.docs.main", caller_symbols)
        self.assertIn(
            "generated capabilities page contains a section for every JSON-RPC method",
            caller_symbols,
        )
        self.assertIn(
            "io.github.amichne.kast.api.docs.AnalysisDocsDocumentTest.`generated capabilities page contains a section for every JSON-RPC method`",
            caller_symbols,
        )
        self.assertIn("generated", caller_symbols)
        self.assertIn("markdown", caller_symbols)
        self.assertIn(
            {
                "symbol": "renderCapabilities",
                "kind": "FUNCTION",
                "containingType": "io.github.amichne.kast.api.docs.DocsDocument",
                "fileHint": "analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt",
            },
            callers,
        )
        self.assertIn("main", resolves)
        self.assertIn("io.github.amichne.kast.api.docs.main", resolves)
        self.assertIn("FileOperation", resolves)
        self.assertIn("io.github.amichne.kast.api.contract.FileOperation", resolves)
        self.assertIn("renderCapabilities", resolves)
        self.assertIn("io.github.amichne.kast.api.docs.DocsDocument.renderCapabilities", resolves)
        self.assertIn(
            "generated capabilities page contains a section for every JSON-RPC method",
            resolves,
        )
        self.assertIn(
            "io.github.amichne.kast.api.docs.AnalysisDocsDocumentTest.`generated capabilities page contains a section for every JSON-RPC method`",
            resolves,
        )
        self.assertIn("generated", resolves)
        self.assertIn("markdown", resolves)


if __name__ == "__main__":
    unittest.main()
