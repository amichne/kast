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


class PrePushLauncherTest(unittest.TestCase):
    def run_hook(
        self,
        *,
        gate_exit: int = 0,
        fast_exit: int = 0,
        dirty: bool = False,
        deletion: bool = False,
        other_head: bool = False,
        inherited: dict[str, str] | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        """Exercise the real hook while external launchers record dispatch."""
        with tempfile.TemporaryDirectory(prefix="kast hook parity ") as temporary:
            root = Path(temporary)
            checkout = root / "checkout with spaces"
            checkout.mkdir()
            binary = root / "bin"
            binary.mkdir()
            report = root / "invocation.json"
            git = shutil.which("git")
            _ = self.assertIsNotNone(git)
            environment = {
                **os.environ,
                **(inherited or {}),
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
                "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}",
                "JAVA_HOME": "ambient-jdk",
                "KAST_LAUNCH_REPORT": str(report),
                "KAST_GATE_EXIT": str(gate_exit),
                "KAST_FAST_EXIT": str(fast_exit),
            }
            _ = environment.pop("KAST_PROJECT_LAUNCHER", None)
            local_names = subprocess.check_output(
                [str(git), "rev-parse", "--local-env-vars"], env=environment, text=True
            ).splitlines()
            for name in local_names:
                _ = environment.pop(name, None)
            _ = subprocess.run([str(git), "init", "-q", str(checkout)], check=True, env=environment)
            release_path = checkout / "distribution" / "release"
            release_path.mkdir(parents=True)
            fixture_gate = release_path / "run_product_gate.py"
            _ = fixture_gate.write_text(
                "import json, os, pathlib, sys\n"
                "pathlib.Path(os.environ['KAST_LAUNCH_REPORT']).write_text(json.dumps({\n"
                "    'argv': sys.argv, 'javaHome': os.environ['JAVA_HOME'],\n"
                "    'projectLauncher': os.environ.get('KAST_PROJECT_LAUNCHER', 'absent')}))\n"
                "raise SystemExit(int(os.environ['KAST_GATE_EXIT']))\n"
            )
            fast_path = checkout / ".github" / "scripts" / "ci"
            fast_path.mkdir(parents=True)
            _ = (fast_path / "fast_checks.py").write_text(
                "import os, pathlib, subprocess, sys\n"
                "if sys.argv[1:] != ['pre-push']:\n    raise SystemExit(97)\n"
                "code = int(os.environ['KAST_FAST_EXIT'])\n"
                "if code:\n    raise SystemExit(code)\n"
                "print('fast-checks: passed', flush=True)\n"
                "root = pathlib.Path(__file__).resolve().parents[3]\n"
                "raise SystemExit(subprocess.run([sys.executable, "
                "str(root / 'distribution/release/run_product_gate.py')]).returncode)\n"
            )
            _ = subprocess.run([str(git), "add", "."], cwd=checkout, env=environment, check=True)
            _ = subprocess.run(
                [
                    str(git),
                    "-c",
                    "user.name=Fixture",
                    "-c",
                    "user.email=fixture@example.invalid",
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "commit.gpgsign=false",
                    "commit",
                    "-qm",
                    "fixture",
                ],
                cwd=checkout,
                env=environment,
                check=True,
            )
            head = subprocess.check_output(
                [str(git), "rev-parse", "HEAD"], cwd=checkout, env=environment, text=True
            ).strip()
            launchers = {
                "python3": "import subprocess, sys\nraise SystemExit(subprocess.run([sys.executable, *sys.argv[1:]]).returncode)\n",
                "mise": (
                    "import os, subprocess, sys\n"
                    "if sys.argv[1:4] == ['exec', '--', 'python3']:\n    arguments = sys.argv[4:]\n"
                    "elif sys.argv[1:7] == ['exec', '--', 'uv', 'run', '--locked', '--script']:\n"
                    "    arguments = sys.argv[7:]\n"
                    "else:\n    raise SystemExit(97)\n"
                    "environment = dict(os.environ, JAVA_HOME='project-jdk', KAST_PROJECT_LAUNCHER='mise')\n"
                    "raise SystemExit(subprocess.run([sys.executable, *arguments], env=environment).returncode)\n"
                ),
            }
            for name, source in launchers.items():
                launcher = binary / name
                _ = launcher.write_text(f"#!{sys.executable}\n{source}")
                launcher.chmod(0o755)
            if dirty:
                _ = fixture_gate.write_text(fixture_gate.read_text() + "# uncommitted\n")
            local_sha = "0" * 40 if deletion else "1" * 40 if other_head else head
            result = subprocess.run(
                ["bash", str(gate.ROOT / ".githooks" / "pre-push")],
                cwd=checkout,
                env=environment,
                text=True,
                capture_output=True,
                input=f"refs/heads/fixture {local_sha} refs/heads/fixture {'0' * 40}\n",
                check=False,
            )
            return result, report.read_text() if report.exists() else ""

    def test_pre_push_uses_the_project_launcher(self) -> None:
        result, report = self.run_hook()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("fast-checks: passed", result.stdout)
        observed = json.loads(report)
        self.assertEqual("mise", observed["projectLauncher"])
        self.assertEqual("project-jdk", observed["javaHome"])
        self.assertEqual(1, len(observed["argv"]))
        self.assertTrue(observed["argv"][0].endswith("/distribution/release/run_product_gate.py"))

    def test_pre_push_preserves_gate_failure(self) -> None:
        result, report = self.run_hook(gate_exit=7)
        self.assertEqual(7, result.returncode)
        self.assertEqual("mise", json.loads(report)["projectLauncher"])

    def test_fast_failure_prevents_product_gate_execution(self) -> None:
        result, report = self.run_hook(fast_exit=23)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual("", report)

    def test_rejected_pushes_do_not_start_the_gate(self) -> None:
        for dirty, other_head in ((True, False), (False, True)):
            with self.subTest(dirty=dirty, other_head=other_head):
                result, report = self.run_hook(dirty=dirty, other_head=other_head)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", report)

    def test_deletion_does_not_start_the_gate(self) -> None:
        result, report = self.run_hook(deletion=True)
        self.assertEqual(0, result.returncode)
        self.assertEqual("", report)

    def test_fixture_git_preserves_an_inherited_repository(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast parent repository ") as temporary:
            parent = Path(temporary)
            environment = {
                "PATH": os.environ["PATH"],
                "HOME": str(parent),
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
            }
            _ = subprocess.run(["git", "init", "-q", str(parent)], env=environment, check=True)
            _ = (parent / "sentinel").write_text("parent-owned content\n")
            _ = subprocess.run(["git", "add", "sentinel"], cwd=parent, env=environment, check=True)
            _ = subprocess.run(
                [
                    "git",
                    "-c",
                    "user.name=Fixture",
                    "-c",
                    "user.email=fixture@example.invalid",
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "commit.gpgsign=false",
                    "commit",
                    "-qm",
                    "parent",
                ],
                cwd=parent,
                env=environment,
                check=True,
            )
            head = subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=parent, env=environment
            )
            index = (parent / ".git/index").read_bytes()
            result, report = self.run_hook(
                inherited={
                    "GIT_DIR": str(parent / ".git"),
                    "GIT_WORK_TREE": str(parent),
                    "GIT_INDEX_FILE": str(parent / ".git/index"),
                    "GIT_COMMON_DIR": str(parent / ".git"),
                }
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("mise", json.loads(report)["projectLauncher"])
            self.assertEqual(
                head,
                subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=parent, env=environment),
            )
            self.assertEqual(index, (parent / ".git/index").read_bytes())
            self.assertEqual("parent-owned content\n", (parent / "sentinel").read_text())


if __name__ == "__main__":
    unittest.main()
