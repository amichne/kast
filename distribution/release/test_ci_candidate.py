#!/usr/bin/env python3
"""Release artifact reuse admits only an exact successful CI run and intact candidate."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / ".github/scripts/release/ci-candidate.py"
SPEC = importlib.util.spec_from_file_location("ci_candidate", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
candidate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = candidate
SPEC.loader.exec_module(candidate)

REPOSITORY = "amichne/kast"
REVISION = "a" * 40
VERSION = "0.44.4"


class CiCandidateTest(unittest.TestCase):
    def test_only_successful_main_push_from_the_exact_repository_and_revision_is_reusable(self) -> None:
        artifact = {
            "id": 10,
            "name": f"ci-release-candidate-v{VERSION}-{REVISION}",
            "expired": False,
            "workflow_run": {"id": 20, "head_sha": REVISION, "head_branch": "main"},
        }
        listing = {"total_count": 1, "artifacts": [artifact]}
        run = {
            "path": ".github/workflows/ci.yml",
            "event": "push",
            "head_sha": REVISION,
            "head_branch": "main",
            "status": "completed",
            "conclusion": "success",
            "repository": {"full_name": REPOSITORY},
        }
        self.assertEqual(artifact, candidate.candidate_artifact(listing, REPOSITORY, REVISION, lambda _: run))
        for change in (
            {"conclusion": "failure"},
            {"head_sha": "b" * 40},
            {"event": "pull_request"},
            {"repository": {"full_name": "foreign/kast"}},
        ):
            self.assertIsNone(
                candidate.candidate_artifact(listing, REPOSITORY, REVISION, lambda _: run | change)
            )
        self.assertIsNone(
            candidate.candidate_artifact(
                {"total_count": 1, "artifacts": [artifact | {"expired": True}]},
                REPOSITORY,
                REVISION,
                lambda _: run,
            )
        )

    def test_incomplete_listing_rejects_instead_of_silently_falling_back(self) -> None:
        with self.assertRaises(candidate.CandidateError):
            candidate.candidate_artifact({"total_count": 2, "artifacts": []}, REPOSITORY, REVISION, lambda _: {})

    def test_candidate_checksums_and_source_identity_are_required(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            control = f"kast-control-v{VERSION}-macos-aarch64.tar.gz"
            plugin = f"kast-ide-hosted-v{VERSION}-idea-262.zip"
            names = (
                control,
                plugin,
                f"kast-hosted-catalog-v{VERSION}.json",
                f"kast-module-knowledge-v{VERSION}.json",
                f"kast-sbom-v{VERSION}.cdx.json",
            )
            for name in names[:-1]:
                (directory / name).write_bytes(name.encode())
            sbom = {
                "bomFormat": "CycloneDX",
                "metadata": {
                    "properties": [
                        {"name": "kast:source-revision", "value": REVISION},
                        *(
                            {"name": f"kast:archive:{name}", "value": f"sha256:{candidate.digest(directory / name)}"}
                            for name in (control, plugin)
                        ),
                    ]
                },
            }
            (directory / names[-1]).write_text(json.dumps(sbom))
            for name in names:
                (directory / f"{name}.sha256").write_text(f"{candidate.digest(directory / name)}  {name}\n")
            candidate.validate(directory, VERSION, REVISION)

            (directory / control).write_bytes(b"changed")
            with self.assertRaises(candidate.CandidateError):
                candidate.validate(directory, VERSION, REVISION)
            (directory / control).write_bytes(control.encode())

            sbom["metadata"]["properties"][0]["value"] = "b" * 40
            (directory / names[-1]).write_text(json.dumps(sbom))
            (directory / f"{names[-1]}.sha256").write_text(
                f"{candidate.digest(directory / names[-1])}  {names[-1]}\n"
            )
            with self.assertRaises(candidate.CandidateError):
                candidate.validate(directory, VERSION, REVISION)


if __name__ == "__main__":
    unittest.main()
