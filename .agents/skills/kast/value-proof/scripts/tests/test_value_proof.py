#!/usr/bin/env python3
from __future__ import annotations

import json
import io
import tempfile
import unittest
from pathlib import Path
import sys
from contextlib import redirect_stdout

SCRIPT_DIR = Path(__file__).resolve().parents[1]
VALUE_PROOF_DIR = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_executive_summary import generate_summary_documents
from render_prompts import render_catalog
from run_value_proof import scaffold_workspace


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


class ValueProofScriptTests(unittest.TestCase):
    def test_checked_in_assets_match_value_proof_contract(self) -> None:
        catalog = json.loads((VALUE_PROOF_DIR / "catalog.json").read_text())
        bindings_schema = json.loads((VALUE_PROOF_DIR / "bindings.schema.json").read_text())
        konditional = json.loads((VALUE_PROOF_DIR / "bindings" / "konditional.json").read_text())

        self.assertEqual("kast-value-proof", catalog["skill_name"])
        self.assertEqual(10, len(catalog["cases"]))
        self.assertEqual(
            [
                "vp-disambiguate-member",
                "vp-disambiguate-function",
                "vp-exhaustive-references",
                "vp-sealed-hierarchy-trace",
                "vp-multi-file-rename",
                "vp-edit-and-validate",
                "vp-scaffold-large-class",
                "vp-workspace-discovery",
                "vp-impact-analysis",
                "vp-cross-module-flow",
            ],
            [case["id"] for case in catalog["cases"]],
        )
        self.assertEqual("Kast value-proof codebase bindings", bindings_schema["title"])
        self.assertEqual("konditional", konditional["target_repo"])
        self.assertEqual("Konstrained", konditional["slots"]["SEALED_HIERARCHY"]["symbol"])

    def test_checked_in_catalog_renders_with_konditional_bindings(self) -> None:
        catalog = json.loads((VALUE_PROOF_DIR / "catalog.json").read_text())
        bindings = json.loads((VALUE_PROOF_DIR / "bindings" / "konditional.json").read_text())

        rendered = render_catalog(catalog, bindings)

        prompts = [case["prompt"] for case in rendered["cases"]]
        expectations = [
            expectation
            for case in rendered["cases"]
            for expectation in case.get("expectations", [])
        ]
        self.assertTrue(all("{{" not in prompt and "}}" not in prompt for prompt in prompts))
        self.assertIn("key property on Feature", prompts[0])
        self.assertIn(
            "Result set is scoped to Feature.key — does not include unrelated types",
            expectations,
        )
        self.assertIn("NamespaceRegistry to FeatureRegistry", prompts[4])

    def test_render_catalog_hydrates_nested_slot_fields(self) -> None:
        catalog = {
            "skill_name": "kast-value-proof",
            "version": 1,
            "cases": [
                {
                    "id": "vp-example",
                    "title": "Example",
                    "prompt": "Find {{DISAMBIGUATE_MEMBER.containingType}}.{{DISAMBIGUATE_MEMBER.symbol}} in {{MODULE_LIST.modules}}.",
                    "expected_output": "Rendered prompt.",
                    "expectations": [
                        "Uses {{DISAMBIGUATE_MEMBER.fqName}}",
                    ],
                    "labels": ["disambiguation"],
                    "stage": "candidate",
                    "source": {"kind": "manual"},
                    "promotion": {"required_pass_rate": 1.0, "required_benchmarks": 2},
                }
            ],
        }
        bindings = {
            "target_repo": "demo",
            "workspace_root": "/tmp/demo",
            "slots": {
                "DISAMBIGUATE_MEMBER": {
                    "symbol": "key",
                    "containingType": "Feature",
                    "fqName": "demo.Feature.key",
                },
                "MODULE_LIST": {"modules": ["engine", "json"]},
            },
        }

        rendered = render_catalog(catalog, bindings)

        case = rendered["cases"][0]
        self.assertEqual(
            "Find Feature.key in engine, json.",
            case["prompt"],
        )
        self.assertEqual(["Uses demo.Feature.key"], case["expectations"])
        self.assertEqual("demo", rendered["bindings"]["target_repo"])

    def test_render_catalog_rejects_unknown_slot(self) -> None:
        catalog = {
            "skill_name": "kast-value-proof",
            "version": 1,
            "cases": [{"id": "vp-bad", "prompt": "{{MISSING.symbol}}"}],
        }
        bindings = {"target_repo": "demo", "workspace_root": "/tmp/demo", "slots": {}}

        with self.assertRaisesRegex(ValueError, "MISSING"):
            render_catalog(catalog, bindings)

    def test_scaffold_workspace_creates_run_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            catalog_path = root / "rendered-catalog.json"
            write_json(
                catalog_path,
                {
                    "skill_name": "kast-value-proof",
                    "version": 1,
                    "cases": [
                        {
                            "id": "vp-disambiguate-member",
                            "title": "Disambiguate member",
                            "prompt": "Find Feature.key.",
                            "expected_output": "Scoped references.",
                            "expectations": ["Uses semantic references"],
                            "labels": ["disambiguation"],
                            "stage": "candidate",
                            "source": {"kind": "manual"},
                            "promotion": {"required_pass_rate": 1.0, "required_benchmarks": 2},
                        }
                    ],
                },
            )

            stdout = io.StringIO()
            with redirect_stdout(stdout):
                iteration_dir = scaffold_workspace(
                    catalog_path=catalog_path,
                    workspace_dir=root / "workspace",
                    runs_per_config=2,
                    configs=["with_skill", "without_skill"],
                    iteration="iteration-001",
                    aggregate=False,
                )

            eval_dir = iteration_dir / "eval-vp-disambiguate-member"
            metadata = json.loads((eval_dir / "eval_metadata.json").read_text())
            self.assertEqual("vp-disambiguate-member", metadata["eval_id"])
            run_dir = eval_dir / "with_skill" / "run-1"
            self.assertTrue((run_dir / "outputs").is_dir())
            self.assertTrue((run_dir / "grading.json").is_file())
            manifest = json.loads((iteration_dir / "run_manifest.json").read_text())
            self.assertEqual(4, manifest["run_count"])
            instructions = (run_dir / "run_instructions.md").read_text()
            self.assertIn("Kast skill loaded", instructions)
            self.assertIn("Find Feature.key.", instructions)
            self.assertIn("run_instructions.md", stdout.getvalue())

    def test_generate_summary_documents_reports_category_breakdown(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            benchmark_path = root / "benchmark.json"
            bindings_path = root / "bindings.json"
            output_path = root / "executive-summary.md"
            write_json(bindings_path, {"target_repo": "konditional", "slots": {}})
            write_json(
                benchmark_path,
                {
                    "metadata": {
                        "skill_name": "kast-value-proof",
                        "evals_run": ["vp-disambiguate-member"],
                    },
                    "runs": [
                        {
                            "eval_id": "vp-disambiguate-member",
                            "configuration": "with_skill",
                            "result": {"pass_rate": 1.0},
                            "expectations": [
                                {
                                    "text": "Uses semantic references",
                                    "passed": True,
                                    "evidence": "Used kast_references",
                                }
                            ],
                            "notes": ["Kast used the exact member identity."],
                        },
                        {
                            "eval_id": "vp-disambiguate-member",
                            "configuration": "without_skill",
                            "result": {"pass_rate": 0.0},
                            "expectations": [],
                            "notes": [],
                        },
                    ],
                    "run_summary": {
                        "with_skill": {
                            "pass_rate": {"mean": 1.0},
                            "tokens": {"mean": 1200},
                            "time_seconds": {"mean": 12.5},
                            "tool_calls": {"mean": 3},
                        },
                        "without_skill": {
                            "pass_rate": {"mean": 0.0},
                            "tokens": {"mean": 3000},
                            "time_seconds": {"mean": 20.0},
                            "tool_calls": {"mean": 8},
                        },
                        "delta": {
                            "pass_rate": "+1.00",
                            "tokens": "-1800",
                            "time_seconds": "-7.5",
                        },
                    },
                    "notes": ["Assertion 'Uses semantic references' passes 100% with Kast, 0% without."],
                },
            )

            html_path = generate_summary_documents(
                benchmark_path=benchmark_path,
                bindings_path=bindings_path,
                output_path=output_path,
            )

            markdown = output_path.read_text()
            self.assertIn("# Kast Value Proof: konditional", markdown)
            self.assertIn("Disambiguation", markdown)
            self.assertIn("fewer bugs shipped", markdown)
            self.assertTrue(html_path.exists())
            self.assertIn("<!doctype html>", html_path.read_text().lower())


if __name__ == "__main__":
    unittest.main()
