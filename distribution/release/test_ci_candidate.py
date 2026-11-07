#!/usr/bin/env python3
"""Release artifact reuse admits only an exact successful CI run and intact candidate."""
from __future__ import annotations

import importlib.util
from dataclasses import asdict, dataclass
from contextlib import redirect_stdout
import io
import os
import subprocess
from unittest.mock import call, patch
import json
from pathlib import Path
import sys
import tempfile
from typing import Literal
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


@dataclass(frozen=True)
class ArchiveProperty:
    name: str
    value: str


@dataclass(frozen=True)
class CandidateMetadata:
    properties: tuple[ArchiveProperty, ...]


@dataclass(frozen=True)
class CandidateSbom:
    metadata: CandidateMetadata
    bomFormat: Literal["CycloneDX"] = "CycloneDX"


class SelectedHostInventoryTest(unittest.TestCase):
    # These literal names are the producer's independent 262/263 record contract.
    INVENTORIES = (
        (
            "262",
            (f"kast-ide-hosted-v{VERSION}-idea-262.zip",),
            (f"kast-host-release-v{VERSION}.json",),
        ),
        (
            "263",
            (f"kast-ide-hosted-v{VERSION}-idea-263.zip",),
            (f"kast-host-release-v{VERSION}-idea-263.json",),
        ),
        (
            "paired",
            (
                f"kast-ide-hosted-v{VERSION}-idea-262.zip",
                f"kast-ide-hosted-v{VERSION}-idea-263.zip",
            ),
            (
                f"kast-host-release-v{VERSION}.json",
                f"kast-host-release-v{VERSION}-idea-263.json",
            ),
        ),
    )

    @staticmethod
    def materialize(directory: Path, plugins: tuple[str, ...], records: tuple[str, ...]) -> None:
        archives = (
            f"kast-control-v{VERSION}-macos-aarch64.tar.gz",
            *plugins,
            f"kast-plugin-v{VERSION}.zip",
            f"kast-marketplace-v{VERSION}.zip",
        )
        sbom_name = f"kast-sbom-v{VERSION}.cdx.json"
        names = archives + (
            f"kast-control-release-v{VERSION}.json",
            *records,
            "host-installation.py",
            f"kast-hosted-catalog-v{VERSION}.json",
            f"kast-module-knowledge-v{VERSION}.json",
            sbom_name,
        )
        for name in names[:-1]:
            (directory / name).write_bytes(name.encode())
        properties = (ArchiveProperty("kast:source-revision", REVISION),) + tuple(
            ArchiveProperty(f"kast:archive:{name}", f"sha256:{candidate.digest(directory / name)}")
            for name in archives
        )
        (directory / sbom_name).write_text(
            json.dumps(asdict(CandidateSbom(CandidateMetadata(properties))))
        )
        for name in names:
            (directory / f"{name}.sha256").write_text(
                f"{candidate.digest(directory / name)}  {name}\n"
            )

    def test_each_produced_host_inventory_is_admitted(self) -> None:
        for label, plugins, records in self.INVENTORIES:
            with self.subTest(hosts=label), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                self.materialize(directory, plugins, records)
                candidate.validate(directory, VERSION, REVISION)

    def test_263_cannot_substitute_a_checksummed_generic_262_record(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.materialize(
                directory,
                (f"kast-ide-hosted-v{VERSION}-idea-263.zip",),
                (f"kast-host-release-v{VERSION}.json",),
            )
            with self.assertRaisesRegex(candidate.CandidateError, "inventory"):
                candidate.validate(directory, VERSION, REVISION)

    def test_each_selected_record_is_required_and_its_checksum_remains_binding(self) -> None:
        for label, plugins, records in self.INVENTORIES:
            for record in records:
                for mutation in ("missing-record", "missing-checksum", "changed-record"):
                    with (
                        self.subTest(hosts=label, record=record, mutation=mutation),
                        tempfile.TemporaryDirectory() as temporary,
                    ):
                        directory = Path(temporary)
                        self.materialize(directory, plugins, records)
                        if mutation == "missing-record":
                            (directory / record).unlink()
                        elif mutation == "missing-checksum":
                            (directory / f"{record}.sha256").unlink()
                        else:
                            (directory / record).write_bytes(b"changed selected record")
                        reason = "checksum" if mutation == "changed-record" else "inventory"
                        with self.assertRaisesRegex(candidate.CandidateError, reason):
                            candidate.validate(directory, VERSION, REVISION)


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
                f"kast-plugin-v{VERSION}.zip",
                f"kast-marketplace-v{VERSION}.zip",
                f"kast-control-release-v{VERSION}.json",
                f"kast-host-release-v{VERSION}.json",
                "host-installation.py",
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
                            for name in names[:4]
                        ),
                    ]
                },
            }
            (directory / names[-1]).write_text(json.dumps(sbom))
            for name in names:
                (directory / f"{name}.sha256").write_text(f"{candidate.digest(directory / name)}  {name}\n")
            candidate.validate(directory, VERSION, REVISION)

            # The same immutable candidate also authenticates the second line.
            variant = f"kast-ide-hosted-v{VERSION}-idea-263.zip"
            variant_record = f"kast-host-release-v{VERSION}-idea-263.json"
            (directory / variant).write_bytes(b"263 Host")
            (directory / variant_record).write_bytes(b"263 record")
            sbom["metadata"]["properties"].append(
                asdict(ArchiveProperty(f"kast:archive:{variant}", f"sha256:{candidate.digest(directory / variant)}"))
            )
            (directory / names[-1]).write_text(json.dumps(sbom))
            for name in (variant, variant_record, names[-1]):
                (directory / f"{name}.sha256").write_text(f"{candidate.digest(directory / name)}  {name}\n")
            candidate.validate(directory, VERSION, REVISION)
            (directory / variant_record).unlink()
            with self.assertRaisesRegex(candidate.CandidateError, "inventory"):
                candidate.validate(directory, VERSION, REVISION)
            for name in (variant, variant + ".sha256", variant_record + ".sha256"):
                (directory / name).unlink()
            sbom["metadata"]["properties"].pop()
            (directory / names[-1]).write_text(json.dumps(sbom))
            (directory / f"{names[-1]}.sha256").write_text(f"{candidate.digest(directory / names[-1])}  {names[-1]}\n")

            for name in names[2:6]:
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
                            f"kast-plugin-v{VERSION}.zip", f"kast-marketplace-v{VERSION}.zip")
                for name in (*archives, f"kast-control-release-v{VERSION}.json", f"kast-host-release-v{VERSION}.json", "host-installation.py", f"kast-hosted-catalog-v{VERSION}.json", f"kast-module-knowledge-v{VERSION}.json"):
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
