#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from prove_consolidation import build_consolidation_report
from validation import (
    build_overlap_report,
    load_pain_point_source,
    validate_benchmark_data,
    validate_skill_directory,
)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


class ValidationTests(unittest.TestCase):
    def create_valid_skill(
        self,
        root: Path,
        *,
        name: str = "example-skill",
        description: str = "Create reusable skills with deterministic eval scaffolding.",
    ) -> Path:
        skill_dir = root / name
        skill_dir.mkdir(parents=True, exist_ok=True)
        write_text(
            skill_dir / "SKILL.md",
            "\n".join(
                [
                    "---",
                    f"name: {name}",
                    f"description: {description}",
                    "---",
                    "",
                    "# Example Skill",
                    "",
                    "Use deterministic helpers and evals.",
                    "",
                ]
            ),
        )
        write_text(skill_dir / "evals" / "files" / "input.txt", "hello\n")
        write_text(
            skill_dir / "evals" / "catalog.json",
            json.dumps(
                {
                    "skill_name": name,
                    "version": 1,
                    "cases": [
                        {
                            "id": "smoke-case",
                            "title": "Smoke case",
                            "prompt": "Read evals/files/input.txt and summarize it.",
                            "files": ["evals/files/input.txt"],
                            "expected_output": "A summary of the input file.",
                            "expectations": ["The response references the input file."],
                            "labels": ["smoke"],
                            "stage": "candidate",
                            "source": {"kind": "manual"},
                            "promotion": {
                                "required_pass_rate": 1.0,
                                "required_benchmarks": 2,
                            },
                        }
                    ],
                },
                indent=2,
            )
            + "\n",
        )
        write_text(skill_dir / "evals" / "pain_points.jsonl", "")
        write_text(
            skill_dir / "history" / "progression.json",
            json.dumps(
                {
                    "skill_name": name,
                    "updated_at": "2026-05-01T00:00:00Z",
                    "benchmarks": [],
                    "case_history": {},
                },
                indent=2,
            )
            + "\n",
        )
        return skill_dir

    def test_valid_skill_directory_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            skill_dir = self.create_valid_skill(Path(temp_dir))
            report = validate_skill_directory(skill_dir, audit_collection=False)
            self.assertTrue(report.is_valid, report.errors)
            self.assertEqual([], report.errors)

    def test_eval_contract_requires_history_and_pain_points(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            skill_dir = Path(temp_dir) / "example-skill"
            skill_dir.mkdir(parents=True, exist_ok=True)
            write_text(
                skill_dir / "SKILL.md",
                "---\nname: example-skill\ndescription: Reusable deterministic skill.\n---\n",
            )
            write_text(
                skill_dir / "evals" / "catalog.json",
                json.dumps({"skill_name": "example-skill", "version": 1, "cases": []}) + "\n",
            )

            report = validate_skill_directory(skill_dir, audit_collection=False)
            self.assertFalse(report.is_valid)
            joined = "\n".join(report.errors)
            self.assertIn("evals/pain_points.jsonl is required", joined)
            self.assertIn("history/progression.json is required", joined)

    def test_catalog_rejects_non_canonical_eval_file_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            skill_dir = self.create_valid_skill(Path(temp_dir))
            catalog_path = skill_dir / "evals" / "catalog.json"
            catalog = json.loads(catalog_path.read_text())
            catalog["cases"][0]["files"] = ["fixtures/input.txt"]
            catalog_path.write_text(json.dumps(catalog, indent=2) + "\n")

            report = validate_skill_directory(skill_dir, audit_collection=False)
            self.assertFalse(report.is_valid)
            self.assertIn("must stay under evals/files/", "\n".join(report.errors))

    def test_invalid_catalog_json_is_reported_cleanly(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            skill_dir = self.create_valid_skill(Path(temp_dir))
            (skill_dir / "evals" / "catalog.json").write_text("{not valid json}\n")

            report = validate_skill_directory(skill_dir, audit_collection=False)
            self.assertFalse(report.is_valid)
            self.assertIn("Invalid JSON", "\n".join(report.errors))

    def test_benchmark_schema_requires_expectation_evidence(self) -> None:
        benchmark = {
            "metadata": {
                "skill_name": "example-skill",
                "skill_path": "/tmp/example-skill",
                "executor_model": "gpt-5.4",
                "analyzer_model": "gpt-5.4",
                "timestamp": "2026-05-01T00:00:00Z",
                "evals_run": ["smoke-case"],
                "runs_per_configuration": 1,
            },
            "runs": [
                {
                    "eval_id": "smoke-case",
                    "configuration": "with_skill",
                    "run_number": 1,
                    "result": {
                        "pass_rate": 1.0,
                        "passed": 1,
                        "failed": 0,
                        "total": 1,
                        "time_seconds": 1.2,
                        "tokens": 10,
                        "tool_calls": 1,
                        "errors": 0,
                    },
                    "expectations": [{"text": "Met expectation", "passed": True}],
                    "notes": [],
                }
            ],
            "run_summary": {
                "with_skill": {
                    "pass_rate": {"mean": 1.0, "stddev": 0.0, "min": 1.0, "max": 1.0},
                    "time_seconds": {"mean": 1.2, "stddev": 0.0, "min": 1.2, "max": 1.2},
                    "tokens": {"mean": 10.0, "stddev": 0.0, "min": 10.0, "max": 10.0},
                },
                "delta": {"pass_rate": "+0.00", "time_seconds": "+0.0", "tokens": "+0"},
            },
            "notes": [],
        }

        report = validate_benchmark_data(benchmark, path=Path("benchmark.json"))
        self.assertFalse(report.is_valid)
        self.assertIn("expectations[0].evidence", "\n".join(report.errors))

    def test_overlap_audit_warns_for_similar_sibling_skills(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            skill_dir = self.create_valid_skill(
                root,
                name="feature-controller-helper",
                description="Create feature controller helpers with deterministic tests and fixtures.",
            )
            self.create_valid_skill(
                root,
                name="feature-controller-kit",
                description="Create feature controller helpers with deterministic tests and fixtures.",
            )

            report = validate_skill_directory(skill_dir, skills_root=root)
            self.assertTrue(report.is_valid, report.errors)
            self.assertTrue(
                any("overlaps with sibling" in warning for warning in report.warnings),
                report.warnings,
            )

    def test_collection_overlap_report_finds_existing_sibling_overlap(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_skill(
                root,
                name="defect-rca",
                description=(
                    "Investigate defect RCA patterns, draft Jira ticket follow-up comments, "
                    "and summarize fix decisions."
                ),
            )
            self.create_valid_skill(
                root,
                name="jira-rca-comment",
                description=(
                    "Draft Jira RCA ticket comments from defect investigations and post "
                    "follow-up fix summaries."
                ),
            )

            report = build_overlap_report(root)
            pairs = {(item["skill_a"], item["skill_b"]) for item in report["findings"]}
            self.assertIn(("defect-rca", "jira-rca-comment"), pairs)

    def test_collection_overlap_report_ignores_generic_test_terms(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_skill(
                root,
                name="billing-exporter",
                description="Create billing CSV exporters and unit tests for the MSL project.",
            )
            self.create_valid_skill(
                root,
                name="profile-avatar-uploader",
                description="Create profile avatar uploaders and unit tests for the MSL project.",
            )

            report = build_overlap_report(root)
            self.assertEqual([], report["findings"])

    def test_scope_audit_warns_for_tree_specific_skill(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            skill_dir = self.create_valid_skill(
                Path(temp_dir),
                name="backend-service-client-instructions",
                description="Create backend-service-clients helpers in src/main and src/test with repo-specific wiring.",
            )

            report = validate_skill_directory(skill_dir, audit_collection=False)
            self.assertTrue(report.is_valid, report.errors)
            self.assertTrue(
                any("Prefer AGENTS.md guidance" in warning for warning in report.warnings),
                report.warnings,
            )

    def test_pain_point_source_rejects_missing_suggested_eval(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pain_points_path = Path(temp_dir) / "pain_points.jsonl"
            pain_points_path.write_text(
                json.dumps(
                    {
                        "id": "missing-suggested-eval",
                        "title": "Bad record",
                        "summary": "Missing suggested eval",
                        "labels": ["candidate"],
                        "source": {"kind": "manual"},
                    }
                )
                + "\n"
            )

            with self.assertRaisesRegex(ValueError, "suggested_eval"):
                load_pain_point_source(pain_points_path)

    def test_consolidation_report_supports_non_regressive_candidate(self) -> None:
        benchmark = {
            "runs": [
                {"eval_id": "case-a", "configuration": "merged-skill", "result": {"pass_rate": 1.0}},
                {"eval_id": "case-b", "configuration": "merged-skill", "result": {"pass_rate": 0.8}},
                {"eval_id": "case-a", "configuration": "legacy-alpha", "result": {"pass_rate": 0.7}},
                {"eval_id": "case-b", "configuration": "legacy-alpha", "result": {"pass_rate": 0.8}},
                {"eval_id": "case-a", "configuration": "legacy-beta", "result": {"pass_rate": 0.6}},
                {"eval_id": "case-b", "configuration": "legacy-beta", "result": {"pass_rate": 0.5}},
            ],
            "run_summary": {
                "merged-skill": {
                    "pass_rate": {"mean": 0.9},
                    "time_seconds": {"mean": 12.0},
                    "tokens": {"mean": 2000.0},
                },
                "legacy-alpha": {
                    "pass_rate": {"mean": 0.75},
                    "time_seconds": {"mean": 11.0},
                    "tokens": {"mean": 1800.0},
                },
                "legacy-beta": {
                    "pass_rate": {"mean": 0.55},
                    "time_seconds": {"mean": 10.0},
                    "tokens": {"mean": 1700.0},
                },
            },
        }

        report = build_consolidation_report(
            benchmark,
            candidate_config="merged-skill",
            baseline_configs=["legacy-alpha", "legacy-beta"],
        )

        self.assertTrue(report["consolidation_supported"])
        self.assertEqual("supported", report["verdict"])
        self.assertEqual(1, report["summary"]["improved_cases"])
        self.assertEqual(1, report["summary"]["matched_cases"])
        self.assertEqual(0, report["summary"]["regressed_cases"])
        self.assertEqual("+0.25", report["summary"]["delta_vs_average_legacy"])

    def test_consolidation_report_flags_regression_against_legacy_envelope(self) -> None:
        benchmark = {
            "runs": [
                {"eval_id": "case-a", "configuration": "merged-skill", "result": {"pass_rate": 0.8}},
                {"eval_id": "case-a", "configuration": "legacy-alpha", "result": {"pass_rate": 1.0}},
                {"eval_id": "case-a", "configuration": "legacy-beta", "result": {"pass_rate": 0.6}},
            ],
            "run_summary": {
                "merged-skill": {
                    "pass_rate": {"mean": 0.8},
                    "time_seconds": {"mean": 12.0},
                    "tokens": {"mean": 2000.0},
                },
                "legacy-alpha": {
                    "pass_rate": {"mean": 1.0},
                    "time_seconds": {"mean": 11.0},
                    "tokens": {"mean": 1800.0},
                },
                "legacy-beta": {
                    "pass_rate": {"mean": 0.6},
                    "time_seconds": {"mean": 10.0},
                    "tokens": {"mean": 1700.0},
                },
            },
        }

        report = build_consolidation_report(
            benchmark,
            candidate_config="merged-skill",
            baseline_configs=["legacy-alpha", "legacy-beta"],
        )

        self.assertFalse(report["consolidation_supported"])
        self.assertEqual("not_supported", report["verdict"])
        self.assertEqual(1, report["summary"]["regressed_cases"])


if __name__ == "__main__":
    unittest.main()
