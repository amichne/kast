"""A metadata fixture receipt cannot stand in for another target or a real IDE trial."""

import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path

import dual_candidate as candidate
import installed_target_evidence as evidence


class InstalledTargetEvidenceTest(unittest.TestCase):
    def test_closed_receipt_preserves_exact_target_scope_artifacts_and_completed_checks(
        self,
    ) -> None:
        artifact = evidence.Artifact("control", "a" * 64, 1)
        expected = evidence.Report(
            1,
            "INSTALLATION_CONTRACT_METADATA_FIXTURE",
            "PASSED",
            "263",
            "263.123.45",
            "263.123.45-IJ",
            "b" * 64,
            "0.0.3",
            "0.0.3",
            "c" * 40,
            "d" * 40,
            artifact,
            replace(artifact, fileName="host"),
            replace(artifact, fileName="record"),
            False,
            evidence.CHECKS,
        )
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            path.write_text(json.dumps(asdict(expected)))
            self.assertEqual(expected, evidence.admit(path, expected))
            for invalid in (
                replace(expected, intellijReleaseLine="262"),
                replace(expected, runtimeIdeLaunched=True),
                replace(expected, completedChecks=evidence.CHECKS[:-1]),
                replace(expected, completedChecks=evidence.CHECKS[::-1]),
                replace(expected, sourceTree="e" * 40),
                replace(expected, hostArtifact=replace(artifact, fileName="host", sha256="f" * 64)),
            ):
                path.write_text(json.dumps(asdict(invalid)))
                self.assertIsInstance(evidence.admit(path, expected), candidate.Failure)
            for change in (
                {"schemaVersion": True},
                {"extra": "ignored"},
                {"controlArtifact": asdict(artifact) | {"bytes": True}},
            ):
                path.write_text(json.dumps(asdict(expected) | change))
                self.assertEqual(candidate.Failure.INVALID_RECORD, evidence.admit(path, expected))


if __name__ == "__main__":
    unittest.main()
