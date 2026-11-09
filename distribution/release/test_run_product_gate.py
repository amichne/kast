#!/usr/bin/env python3
"""Source-bound local qualification without network or Gradle."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_product_gate as gate


class ProductGateVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kast local qualification ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.environment = {
            key: value for key, value in os.environ.items() if not key.startswith("GIT_")
        }
        self.environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        self.git("init", "-q")
        (self.root / ".gitignore").write_text("build/\n")
        admission = self.root / ".github/scripts/release/admit-source.sh"
        admission.parent.mkdir(parents=True)
        shutil.copyfile(gate.ROOT / ".github/scripts/release/admit-source.sh", admission)
        launcher = self.root / "gradlew"
        launcher.write_text(
            f"#!{sys.executable}\n"
            + """import json, os, pathlib, signal, subprocess, sys
root = pathlib.Path.cwd()
(root / "build").mkdir(exist_ok=True)
(root / "build/argv.json").write_text(json.dumps(sys.argv[1:]))
(root / "build/owner.json").write_text(json.dumps(os.environ.get("KAST_CI_GRADLE_INVOCATION")))
(root / "build/git-environment.json").write_text(json.dumps({key: os.environ.get(key) for key in ("GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM", "GIT_NO_REPLACE_OBJECTS")}))
if "--dry-run" in sys.argv:
    raise SystemExit(0)
properties = dict(arg[2:].split("=", 1) for arg in sys.argv[1:] if arg.startswith("-P"))
identity = {"version": properties["controlVersion"], "sourceRevision": properties["kastSourceRevision"],
            "sourceTree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip()}
mode = os.environ.get("KAST_GATE_FIXTURE", "pass")
if mode == "fail": raise SystemExit(7)
if mode == "signal": os.kill(os.getpid(), signal.SIGTERM)
if mode == "dirty": (root / "untracked-source").write_text("changed")
if mode == "head":
    subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                    "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                    "commit", "--allow-empty", "-qm", "moved"], check=True)
if mode == "stale": identity["sourceRevision"] = "a" * 40
if mode == "wrong-version": identity["version"] = "0.50.3"
if mode == "wrong-tree": identity["sourceTree"] = "b" * 40
if mode == "missing": raise SystemExit(0)
path = root / "build/control-product/share/kast/build-identity.json"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text("[]" if mode == "malformed" else json.dumps(identity))
"""
        )
        launcher.chmod(0o755)
        self.git("add", ".")
        self.commit("fixture")
        self.root_patch = patch.object(gate, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.env_patch = patch.dict(os.environ, self.environment, clear=True)
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)

    def git(self, *arguments):
        return subprocess.check_output(
            ["git", *arguments], cwd=self.root, env=self.environment, text=True
        ).strip()

    def commit(self, message):
        self.git(
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            message,
        )

    def report(self):
        return json.loads((self.root / "build/reports/local-product-gate.json").read_text())

    def test_source_identity_and_versions_are_explicit_without_release_catalog(self):
        with patch.dict(
            os.environ,
            {
                "ORG_GRADLE_PROJECT_controlVersion": "9.9.9",
                "ORG_GRADLE_PROJECT_hostedPluginVersion": "8.8.8",
            },
        ):
            self.assertEqual(0, gate.run_gate())
        arguments = json.loads((self.root / "build/argv.json").read_text())
        for value in (
            "-Pversion=0.0.0",
            "-PcontrolVersion=0.0.0",
            "-PhostedPluginVersion=0.0.0",
            "-PkastSourceRevision=" + self.git("rev-parse", "HEAD"),
            "productBuildGate",
            "--max-workers=2",
            "-Dorg.gradle.jvmargs=-Xmx5g",
            "--no-daemon",
        ):
            self.assertIn(value, arguments)
        self.assertEqual("VALIDATED", self.report()["outcome"])
        self.assertEqual(self.git("rev-parse", "HEAD"), self.report()["sourceRevision"])
        self.assertEqual(self.git("rev-parse", "HEAD^{tree}"), self.report()["sourceTree"])
        self.assertEqual("LOCAL_VALIDATION", self.report()["role"])
        self.assertEqual(
            {
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_NO_REPLACE_OBJECTS": "1",
            },
            json.loads((self.root / "build/git-environment.json").read_text()),
        )

    def test_dirty_source_never_starts_gradle(self):
        for mode in ("untracked", "unstaged", "staged"):
            with self.subTest(mode=mode):
                target = self.root / ("new-source" if mode == "untracked" else ".gitignore")
                original = target.read_text() if target.exists() else None
                target.write_text("changed\n")
                if mode == "staged":
                    self.git("add", str(target))
                self.assertNotEqual(0, gate.run_gate())
                self.assertFalse((self.root / "build/argv.json").exists())
                self.assertEqual("SOURCE_REJECTED", self.report()["outcome"])
                if mode == "staged":
                    self.git("reset", "-q", "HEAD", "--", str(target))
                if original is None:
                    target.unlink()
                else:
                    target.write_text(original)

    def test_failure_drift_and_artifact_mismatch_cannot_retain_success(self):
        for mode, outcome, code in (
            ("fail", "GRADLE_FAILED", 7),
            ("signal", "GRADLE_FAILED", 143),
            ("dirty", "SOURCE_REJECTED", 1),
            ("head", "SOURCE_REJECTED", 1),
            ("stale", "IDENTITY_REJECTED", 1),
            ("wrong-version", "IDENTITY_REJECTED", 1),
            ("wrong-tree", "IDENTITY_REJECTED", 1),
            ("missing", "IDENTITY_REJECTED", 1),
            ("malformed", "IDENTITY_REJECTED", 1),
        ):
            with self.subTest(mode=mode):
                self.assertEqual(0, gate.run_gate())
                identity = self.root / "build/control-product/share/kast/build-identity.json"
                identity.unlink()
                with patch.dict(os.environ, {"KAST_GATE_FIXTURE": mode}):
                    self.assertEqual(code, gate.run_gate())
                self.assertEqual(outcome, self.report()["outcome"])
                (self.root / "untracked-source").unlink(missing_ok=True)

    def test_dry_run_cannot_claim_completed_qualification(self):
        self.assertEqual(0, gate.run_gate())
        self.assertNotIn("--info", json.loads((self.root / "build/argv.json").read_text()))
        with patch.dict(os.environ, {"KAST_CI_GRADLE_INVOCATION": "owned-graph"}):
            self.assertEqual(0, gate.run_gate(dry_run=True))
        self.assertEqual("DRY_RUN", self.report()["outcome"])
        self.assertIn("--info", json.loads((self.root / "build/argv.json").read_text()))
        self.assertEqual("owned-graph", json.loads((self.root / "build/owner.json").read_text()))

    def test_inherited_git_context_cannot_redirect_admission_or_build(self):
        with patch.dict(
            os.environ,
            {
                "GIT_DIR": "/nonexistent/foreign.git",
                "GIT_WORK_TREE": "/foreign",
                "GIT_INDEX_FILE": "/foreign/index",
                "GIT_COMMON_DIR": "/foreign",
            },
        ):
            self.assertEqual(0, gate.run_gate())
        self.assertEqual(self.git("rev-parse", "HEAD"), self.report()["sourceRevision"])

    def test_identity_uses_full_commit_and_tree_not_history_depth_or_tags(self):
        self.assertEqual(0, gate.run_gate())
        first = self.report()
        self.git("tag", "v99.99.99")
        self.assertEqual(0, gate.run_gate())
        self.assertEqual(first, self.report())
        self.git(
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "--allow-empty",
            "-qm",
            "same tree",
        )
        self.assertEqual(0, gate.run_gate())
        self.assertEqual(first["sourceTree"], self.report()["sourceTree"])
        self.assertNotEqual(first["sourceRevision"], self.report()["sourceRevision"])
        with tempfile.TemporaryDirectory() as cloned:
            subprocess.run(
                ["git", "clone", "-q", "--depth=1", self.root.as_uri(), cloned],
                env=self.environment,
                check=True,
            )
            expected = self.report()
            with patch.object(gate, "ROOT", Path(cloned)):
                self.assertEqual(0, gate.run_gate())
            actual = json.loads(
                (Path(cloned) / "build/reports/local-product-gate.json").read_text()
            )
            self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
