#!/usr/bin/env python3
"""Release artifact reuse admits only an exact successful CI run and intact candidate."""
from __future__ import annotations

import importlib.util
from contextlib import redirect_stdout
import io
import os
import subprocess
from unittest.mock import call, patch
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
                f"kast-skill-v{VERSION}.zip",
                f"kast-plugin-v{VERSION}.zip",
                f"kast-marketplace-v{VERSION}.zip",
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
                            for name in names[:-3]
                        ),
                    ]
                },
            }
            (directory / names[-1]).write_text(json.dumps(sbom))
            for name in names:
                (directory / f"{name}.sha256").write_text(f"{candidate.digest(directory / name)}  {name}\n")
            candidate.validate(directory, VERSION, REVISION)

            for name in names[2:5]:
                with self.subTest(agent_archive=name):
                    original = (directory / name).read_bytes()
                    (directory / name).unlink()
                    with self.assertRaisesRegex(candidate.CandidateError, "inventory"):
                        candidate.validate(directory, VERSION, REVISION)
                    (directory / name).write_bytes(b"changed-agent-payload")
                    with self.assertRaisesRegex(candidate.CandidateError, "checksum"):
                        candidate.validate(directory, VERSION, REVISION)
                    (directory / name).write_bytes(original)

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


def run_document(status="in_progress"):
    return {
        "id": 20, "path": ".github/workflows/ci.yml", "event": "push", "head_sha": REVISION,
        "head_branch": "main", "status": status, "conclusion": None,
        "repository": {"full_name": REPOSITORY},
    }


class PendingCandidateTest(unittest.TestCase):
    def test_all_active_states_block_fallback_even_before_an_artifact_exists(self):
        for status in ("queued", "in_progress", "requested", "waiting", "pending"):
            with self.subTest(status=status):
                listing = {"total_count": 1, "workflow_runs": [run_document(status)]}
                self.assertTrue(candidate.has_pending_candidate(listing, REPOSITORY, REVISION))

    def test_other_sources_workflows_and_repositories_do_not_block(self):
        for change in (
            {"head_sha": "b" * 40}, {"head_branch": "feature"}, {"event": "pull_request"},
            {"path": ".github/workflows/developer-release.yml"}, {"repository": {"full_name": "foreign/kast"}},
        ):
            with self.subTest(change=change):
                listing = {"total_count": 1, "workflow_runs": [run_document() | change]}
                self.assertFalse(candidate.has_pending_candidate(listing, REPOSITORY, REVISION))

    def test_completed_or_absent_producers_allow_fallback(self):
        self.assertFalse(candidate.has_pending_candidate({"total_count": 0, "workflow_runs": []}, REPOSITORY, REVISION))
        for conclusion in ("success", "failure", "cancelled", "timed_out"):
            with self.subTest(conclusion=conclusion):
                run = run_document("completed") | {"conclusion": conclusion}
                self.assertFalse(candidate.has_pending_candidate({"total_count": 1, "workflow_runs": [run]}, REPOSITORY, REVISION))

    def test_incomplete_malformed_or_unknown_observations_reject(self):
        for listing in (
            {}, {"total_count": 2, "workflow_runs": [run_document()]},
            {"total_count": True, "workflow_runs": [run_document()]},
            {"total_count": 1, "workflow_runs": [None]},
            {"total_count": 1, "workflow_runs": [{}]},
            {"total_count": 1, "workflow_runs": [run_document("unknown")]},
            {"total_count": 2, "workflow_runs": [run_document(), run_document("unknown")]},
        ):
            with self.subTest(listing=listing), self.assertRaises(candidate.CandidateError):
                candidate.has_pending_candidate(listing, REPOSITORY, REVISION)

    def test_pending_and_missing_do_not_create_directories_or_download(self):
        for runs, expected in (([run_document()], candidate.CandidateState.PENDING),
                               ([], candidate.CandidateState.MISSING)):
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary) / "candidate"
                with patch.object(candidate, "github_json", side_effect=[
                    {"total_count": len(runs), "workflow_runs": runs},
                    {"total_count": 0, "artifacts": []},
                ]) as observe, patch.object(candidate.subprocess, "run") as execute:
                    self.assertEqual(expected, candidate.reuse(REPOSITORY, VERSION, REVISION, directory))
                self.assertEqual([
                    call(f"repos/{REPOSITORY}/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha={REVISION}&per_page=100"),
                    call(f"repos/{REPOSITORY}/actions/artifacts?name=ci-release-candidate-v{VERSION}-{REVISION}&per_page=100"),
                ], observe.call_args_list)
                execute.assert_not_called()
                self.assertFalse(directory.exists())

    def test_observation_rejection_never_turns_into_missing(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            with patch.object(candidate, "github_json", side_effect=candidate.CandidateError("unavailable")), \
                    patch.object(candidate.subprocess, "run") as execute, self.assertRaises(candidate.CandidateError):
                candidate.reuse(REPOSITORY, VERSION, REVISION, directory)
            execute.assert_not_called()
            self.assertFalse(directory.exists())

    def test_pending_cli_exits_nonzero_and_retains_its_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "github-output"
            arguments = ["ci-candidate.py", "reuse", "--repository", REPOSITORY, "--version", VERSION,
                         "--source-revision", REVISION, "--directory", str(root / "candidate")]
            with patch.object(sys, "argv", arguments), patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), \
                    patch.object(candidate, "github_json", side_effect=lambda endpoint: {
                        f"repos/{REPOSITORY}/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha={REVISION}&per_page=100":
                            {"total_count": 1, "workflow_runs": [run_document()]},
                        f"repos/{REPOSITORY}/actions/artifacts?name=ci-release-candidate-v{VERSION}-{REVISION}&per_page=100":
                            {"total_count": 0, "artifacts": []},
                    }[endpoint]), patch.object(candidate.subprocess, "run") as execute, redirect_stdout(io.StringIO()), \
                    self.assertRaises(SystemExit) as failure:
                candidate.main()
            self.assertNotEqual(0, failure.exception.code)
            self.assertIn("still running", str(failure.exception))
            self.assertEqual("candidate=PENDING\n", output.read_text())
            execute.assert_not_called()
            self.assertFalse((root / "candidate").exists())

    def test_successful_artifact_can_be_reused_when_ci_finishes_between_observations(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            artifact = {"id": 10, "name": f"ci-release-candidate-v{VERSION}-{REVISION}", "expired": False,
                        "workflow_run": {"id": 20, "head_sha": REVISION, "head_branch": "main"}}

            def download(command, **kwargs):
                self.assertEqual(["gh", "run", "download", "20", "--repo", REPOSITORY,
                                  "-n", artifact["name"], "-D", str(directory)], command)
                archives = (f"kast-control-v{VERSION}-macos-aarch64.tar.gz", f"kast-ide-hosted-v{VERSION}-idea-262.zip",
                            f"kast-skill-v{VERSION}.zip", f"kast-plugin-v{VERSION}.zip", f"kast-marketplace-v{VERSION}.zip")
                for name in (*archives, f"kast-hosted-catalog-v{VERSION}.json", f"kast-module-knowledge-v{VERSION}.json"):
                    (directory / name).write_bytes(name.encode())
                sbom = {"bomFormat": "CycloneDX", "metadata": {"properties": [
                    {"name": "kast:source-revision", "value": REVISION},
                    *({"name": f"kast:archive:{name}", "value": f"sha256:{candidate.digest(directory / name)}"} for name in archives),
                ]}}
                (directory / f"kast-sbom-v{VERSION}.cdx.json").write_text(json.dumps(sbom))
                for asset in tuple(directory.iterdir()):
                    asset.with_name(asset.name + ".sha256").write_text(f"{candidate.digest(asset)}  {asset.name}\n")
                return subprocess.CompletedProcess(command, 0)

            with patch.object(candidate, "github_json", side_effect=[
                {"total_count": 1, "workflow_runs": [run_document()]},
                {"total_count": 1, "artifacts": [artifact]},
                run_document("completed") | {"conclusion": "success"},
            ]) as observe, patch.object(candidate.subprocess, "run", side_effect=download) as execute:
                self.assertEqual(candidate.CandidateState.REUSED, candidate.reuse(REPOSITORY, VERSION, REVISION, directory))
            self.assertEqual(3, observe.call_count)
            execute.assert_called_once()
            candidate.validate(directory, VERSION, REVISION)


if __name__ == "__main__":
    unittest.main()
