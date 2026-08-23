#!/usr/bin/env python3
"""Focused standard-library tests for the PR 633 program verifier."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import verify_pr633_program as verifier


class VerifyPr633ProgramTest(unittest.TestCase):
    def test_installed_artifact_is_schema_valid_and_stops_at_gate_070(self) -> None:
        root = Path(__file__).resolve().parents[2]

        result = verifier.verify_artifact(root)

        self.assertEqual("GATE-070", result["gates"][-1])
        self.assertNotIn("GATE-080", result["gates"])

    def test_ci_artifact_rejects_another_head(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "gate.json"
            report.write_text(
                json.dumps(
                    {
                        "gateId": "GATE-060",
                        "headSha": "a" * 40,
                        "status": "passed",
                        "dependencyEvidence": {"GATE-050": "digest"},
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaises(verifier.VerificationFailure):
                verifier.verify_ci_artifact(report, "b" * 40)

    def test_schema_validator_rejects_duplicate_operation_ids(self) -> None:
        schema = {
            "type": "array",
            "uniqueItems": True,
            "items": {"type": "string"},
        }

        with self.assertRaises(verifier.VerificationFailure):
            verifier.validate_schema(["topology.build", "topology.build"], schema, schema)

    def test_lifecycle_schema_rejects_obsolete_report_shape(self) -> None:
        root = Path(__file__).resolve().parents[2]
        schema = json.loads(
            (root / "gradle/pr633/schemas/topology-installed-lifecycle.schema.json").read_text(
                encoding="utf-8",
            )
        )
        obsolete = {
            "schemaVersion": 1,
            "kind": "kast-pr633-installed-topology-lifecycle",
            "staleTopology": {},
        }

        with self.assertRaises(verifier.VerificationFailure):
            verifier.validate_schema(obsolete, schema, schema)


if __name__ == "__main__":
    unittest.main()
