#!/usr/bin/env python3
"""A bootstrap release is one immutable pre-release. Scripted commands reject unexpected and unused calls."""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
from collections.abc import Sequence
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / ".github/scripts/release"))
import publish_bootstrap as publisher
from publish_bootstrap import Completed, Effect, Failure, Rejected

ROOT = Path(__file__).resolve().parents[2]
REVISION = "a" * 40
TREE = "b" * 40
TAG = "bootstrap-" + "b" * 12
CONTENT = {
    "kast-bootstrap-THIRD_PARTY_NOTICES.txt": b"terms",
    "kast-bootstrap-linux-x86_64": b"linux",
    "kast-bootstrap-macos-aarch64": b"macos",
}
SOURCE = ("git rev-parse", Completed(0, f"{REVISION}\n{TREE}\n", ""))
CLEAN = ("git status", Completed(0, "", ""))
ABSENT = ("gh release view", Completed(1, "", "release not found\n"))
NO_TAG = ("gh api", Completed(1, '{"status":"404"}', "gh: Not Found (HTTP 404)\n"))
CREATED = ("gh release create", Completed(0, "https://example\n", ""))
PROMOTED = ("gh release edit", Completed(0, "https://example\n", ""))
STABLE = ("gh api", Completed(0, "v0.52.0\n", ""))


def view(*, draft: bool, prerelease: object = True, assets: object = None) -> tuple[str, Completed]:
    if assets is None:
        assets = [
            {"name": name, "digest": "sha256:" + hashlib.sha256(raw).hexdigest(), "size": len(raw),
             "state": "uploaded", "url": "https://example"}
            for name, raw in CONTENT.items()
        ]  # fmt: skip
    document = {"isDraft": draft, "isPrerelease": prerelease, "assets": assets}
    return ("gh release view", Completed(0, json.dumps(document), ""))


class Script:
    def __init__(self, *steps: tuple[str, Completed]) -> None:
        self.steps = list(steps)
        self.commands: list[tuple[str, ...]] = []

    def __call__(self, command: Sequence[str]) -> Completed:
        self.commands.append(tuple(command))
        if not self.steps or not " ".join(command).startswith(self.steps[0][0]):
            raise AssertionError(f"unexpected command {command}")
        return self.steps.pop(0)[1]

    def writes(self) -> list[tuple[str, ...]]:
        return [
            item
            for item in self.commands
            if item[:3] in {("gh", "release", "create"), ("gh", "release", "edit")}
        ]


class BootstrapReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve() / "bootstrap"
        self.directory.mkdir()
        for name, raw in CONTENT.items():
            _ = (self.directory / name).write_bytes(raw)

    def publish(self, *steps: tuple[str, Completed]) -> publisher.Outcome:
        self.script = Script(*steps)
        try:
            return publisher.publish(self.directory, self.script)
        finally:
            self.assertEqual([], self.script.steps)

    def reject(self, failure: Failure, *steps: tuple[str, Completed], writes: int = 0) -> None:
        with self.assertRaises(Rejected) as rejected:
            _ = self.publish(*steps)
        self.assertEqual(failure, rejected.exception.failure)
        self.assertEqual(writes, len(self.script.writes()))

    def test_a_new_source_becomes_a_verified_prerelease_that_is_never_latest(self) -> None:
        outcome = self.publish(
            SOURCE,
            CLEAN,
            ABSENT,
            NO_TAG,
            CREATED,
            view(draft=True),
            PROMOTED,
            view(draft=False),
            STABLE,
        )
        self.assertEqual(
            (Effect.PUBLISHED, TAG, REVISION),
            (outcome.effect, outcome.bootstrap.tag, outcome.bootstrap.revision),
        )
        create, edit = self.script.writes()
        self.assertEqual(REVISION, create[create.index("--target") + 1])
        self.assertIn("--draft", create)
        self.assertIn("--draft=false", edit)
        for command in (create, edit):
            self.assertIn("--prerelease", command)
            self.assertIn("--latest=false", command)
        self.assertEqual(
            {name: hashlib.sha256(raw).hexdigest() for name, raw in CONTENT.items()},
            {item.name: item.sha256 for item in outcome.bootstrap.files},
        )

    def test_the_same_published_bytes_are_unchanged(self) -> None:
        outcome = self.publish(SOURCE, CLEAN, view(draft=False), STABLE)
        self.assertEqual(Effect.UNCHANGED, outcome.effect)
        self.assertEqual([], self.script.writes())

    def test_other_published_facts_stop_before_a_write(self) -> None:
        terms, good, other = (
            {
                "name": name,
                "digest": "sha256:" + hashlib.sha256(raw).hexdigest(),
                "size": len(raw),
                "state": "uploaded",
            }
            for name, raw in CONTENT.items()
        )
        wrong = {**good, "digest": "sha256:" + "0" * 64}
        for label, release in {
            "missing file": view(draft=False, assets=[terms, good]),
            "missing notices": view(draft=False, assets=[good, other]),
            "other digest": view(draft=False, assets=[terms, wrong, other]),
            "repeated file": view(draft=False, assets=[terms, good, other, other]),
            "stable release": view(draft=False, prerelease=False),
        }.items():
            with self.subTest(label):
                self.reject(Failure.RELEASE_MISMATCH, SOURCE, CLEAN, release)

    def test_drafts_are_left_for_a_person(self) -> None:
        self.reject(Failure.INCOMPLETE_DRAFT, SOURCE, CLEAN, view(draft=True))
        self.reject(Failure.INCOMPLETE_DRAFT, SOURCE, CLEAN, view(draft=True, assets=[]))

    def test_failed_or_malformed_observations_stop_before_a_write(self) -> None:
        for label, steps in {
            "view": (SOURCE, CLEAN, ("gh release view", Completed(1, "", "HTTP 502"))),
            "document": (SOURCE, CLEAN, ("gh release view", Completed(0, "{}", ""))),
            "tag": (
                SOURCE,
                CLEAN,
                ABSENT,
                ("gh api", Completed(1, "", "gh: Bad Gateway (HTTP 502)")),
            ),
        }.items():
            with self.subTest(label):
                self.reject(Failure.OBSERVATION_FAILED, *steps)

    def test_a_tag_without_a_release_stops_before_a_write(self) -> None:
        self.reject(Failure.TAG_EXISTS, SOURCE, CLEAN, ABSENT, ("gh api", Completed(0, "{}", "")))

    def test_a_failed_write_is_reported(self) -> None:
        failed = Completed(1, "", "HTTP 500")
        self.reject(
            Failure.PUBLICATION_FAILED,
            SOURCE,
            CLEAN,
            ABSENT,
            NO_TAG,
            ("gh release create", failed),
            writes=1,
        )
        self.reject(
            Failure.PUBLICATION_FAILED, SOURCE, CLEAN, ABSENT, NO_TAG, CREATED, view(draft=False),
            writes=1,
        )  # fmt: skip
        self.reject(
            Failure.PUBLICATION_FAILED, SOURCE, CLEAN, ABSENT, NO_TAG, CREATED, view(draft=True),
            ("gh release edit", failed), writes=2,
        )  # fmt: skip
        self.reject(
            Failure.PUBLICATION_FAILED, SOURCE, CLEAN, ABSENT, NO_TAG, CREATED, view(draft=True), PROMOTED,
            view(draft=True), writes=2,
        )  # fmt: skip

    def test_a_bootstrap_that_became_the_latest_release_is_reported(self) -> None:
        latest = ("gh api", Completed(0, TAG + "\n", ""))
        self.reject(Failure.LATEST_RELEASE_CHANGED, SOURCE, CLEAN, view(draft=False), latest)

    def test_an_unclean_or_unknown_source_stops_before_github(self) -> None:
        self.reject(
            Failure.SOURCE_REJECTED,
            SOURCE,
            ("git status", Completed(0, " M manager/go.mod\n", "")),
        )
        self.reject(Failure.SOURCE_REJECTED, ("git rev-parse", Completed(0, "HEAD\n", "")), CLEAN)

    def test_only_the_exact_regular_files_are_admitted(self) -> None:
        extra = self.directory / "notes.txt"
        _ = extra.write_text("notes")
        self.reject(Failure.FILES_REJECTED, SOURCE, CLEAN)
        extra.unlink()
        target = self.directory / "kast-bootstrap-macos-aarch64"
        _ = target.write_bytes(b"")
        self.reject(Failure.FILES_REJECTED, SOURCE, CLEAN)
        target.unlink()
        target.symlink_to(self.directory / "kast-bootstrap-linux-x86_64")
        self.reject(Failure.FILES_REJECTED, SOURCE, CLEAN)

    def test_publication_outside_main_runs_no_command(self) -> None:
        for environment in (
            {},
            {"GITHUB_REPOSITORY": "amichne/kast", "GITHUB_REF": "refs/pull/1/merge"},
        ):
            with self.subTest(environment), mock.patch.dict(os.environ, environment, clear=True):
                script = Script()
                self.assertEqual(1, publisher.main(["--directory", str(self.directory)], script))
                self.assertEqual([], script.commands)

    def test_the_workflow_publishes_only_a_dispatch_of_main(self) -> None:
        text = (ROOT / ".github/workflows/bootstrap-release.yml").read_text()
        self.assertIn("on:\n  workflow_dispatch:\n", text)
        self.assertNotIn("pull_request", text)
        self.assertIn("if: github.ref == 'refs/heads/main'\n", text)
        self.assertIn("permissions:\n  contents: read\n", text)
        self.assertEqual(1, text.count("contents: write"))
        # GitHub's implicit shell has no pipefail, so the piped publisher needs an explicit one.
        self.assertIn(
            "shell: bash\n        run: mise run bootstrap:publish", text
        )
        build, publish = text.index("mise run bootstrap:build --output build/bootstrap"), text.index("mise run bootstrap:publish")
        self.assertLess(text.index("mise run bootstrap:gate"), build)
        self.assertLess(build, publish)

    def test_the_published_names_are_the_built_names(self) -> None:
        source = (ROOT / "manager/check").read_text()
        notices, *executables = publisher.NAMES
        for name in executables:
            self.assertIn(f'"{name.removeprefix("kast-bootstrap-")}": (', source)
        self.assertIn(f'NOTICES = "{notices.removeprefix("kast-bootstrap-")}"\n', source)
        self.assertIn('output / f"kast-bootstrap-{NOTICES}"', source)


if __name__ == "__main__":
    unittest.main()
