#!/usr/bin/env python3
"""Fresh release authority and exact version propagation without network or Gradle."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import run_product_gate as gate


def release(tag, *, draft=False, prerelease=False):
    return dict(tag_name=tag, draft=draft, prerelease=prerelease)


class ProductGateVersionTest(unittest.TestCase):
    def test_selects_numeric_latest_published_stable_version(self):
        observed = [release("v0.9.9"), release("v0.10.1"), release("v0.10.0"),
                    release("v9.0.0", draft=True), release("v8.0.0", prerelease=True),
                    release("developer-latest"), {"tag_name": "v7.0.0"}]
        self.assertEqual(gate.Version(0, 10, 1), gate.latest_version(observed))

    def test_no_authoritative_release_is_a_closed_failure(self):
        for observed in ([], [release("nightly")], [release("v1.0.0", prerelease=True)]):
            with self.subTest(observed=observed):
                self.assertEqual(gate.ReleaseFailure.NO_PUBLISHED_STABLE_RELEASE,
                                 gate.latest_version(observed))

    def test_each_invocation_refreshes_and_passes_exact_version_to_gradle(self):
        with patch.object(gate, "observe_releases", side_effect=[
            [release("v0.46.0")], [release("v0.47.0")],
        ]) as observe, patch.object(gate.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(0, gate.run_gate())
            self.assertEqual(0, gate.run_gate(dry_run=True))
        self.assertEqual([(("amichne/kast",), {}), (("amichne/kast",), {})], observe.call_args_list)
        self.assertEqual([
            ((["./gradlew", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx5g", "-Pversion=0.46.0",
               "productBuildGate", "--console=plain", "--profile"],), dict(cwd=gate.ROOT, check=False)),
            ((["./gradlew", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx5g", "-Pversion=0.47.0",
               "productBuildGate", "--console=plain", "--dry-run"],), dict(cwd=gate.ROOT, check=False)),
        ], run.call_args_list)

    def test_missing_release_cannot_start_gradle(self):
        with patch.object(gate, "observe_releases", return_value=[]), patch.object(gate.subprocess, "run") as run:
            with self.assertRaisesRegex(SystemExit, "no-published-stable-release"):
                gate.run_gate()
            run.assert_not_called()

    def test_unavailable_catalog_cannot_start_gradle(self):
        with patch.object(gate, "observe_releases", side_effect=RuntimeError("release-catalog-unavailable")), \
                patch.object(gate.subprocess, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "release-catalog-unavailable"):
                gate.run_gate()
            run.assert_not_called()

    def test_gradle_failure_is_preserved(self):
        with patch.object(gate, "observe_releases", return_value=[release("v0.46.0")]), \
                patch.object(gate.subprocess, "run", return_value=subprocess.CompletedProcess([], 7)):
            self.assertEqual(7, gate.run_gate())


class PrePushLauncherTest(unittest.TestCase):
    def run_hook(self, *, gate_exit: int = 0, dirty: bool = False,
                 deletion: bool = False, other_head: bool = False) -> tuple[subprocess.CompletedProcess[str], str]:
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
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
                "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}",
                "JAVA_HOME": "ambient-jdk",
                "KAST_LAUNCH_REPORT": str(report),
                "KAST_GATE_EXIT": str(gate_exit),
            }
            _ = environment.pop("KAST_PROJECT_LAUNCHER", None)
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
            _ = subprocess.run([str(git), "add", "."], cwd=checkout, env=environment, check=True)
            _ = subprocess.run([
                str(git), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture",
            ], cwd=checkout, env=environment, check=True)
            head = subprocess.check_output([str(git), "rev-parse", "HEAD"], cwd=checkout,
                                           env=environment, text=True).strip()
            launchers = {
                "python3": "import subprocess, sys\nraise SystemExit(subprocess.run([sys.executable, *sys.argv[1:]]).returncode)\n",
                "mise": (
                    "import os, subprocess, sys\n"
                    "if sys.argv[1:4] != ['exec', '--', 'python3']:\n    raise SystemExit(97)\n"
                    "environment = dict(os.environ, JAVA_HOME='project-jdk', KAST_PROJECT_LAUNCHER='mise')\n"
                    "raise SystemExit(subprocess.run([sys.executable, *sys.argv[4:]], env=environment).returncode)\n"
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
                cwd=checkout, env=environment, text=True, capture_output=True,
                input=f"refs/heads/fixture {local_sha} refs/heads/fixture {'0' * 40}\n",
                check=False,
            )
            return result, report.read_text() if report.exists() else ""

    def test_pre_push_uses_the_project_launcher(self) -> None:
        result, report = self.run_hook()
        self.assertEqual(0, result.returncode, result.stderr)
        observed = json.loads(report)
        self.assertEqual("mise", observed["projectLauncher"])
        self.assertEqual("project-jdk", observed["javaHome"])
        self.assertEqual(1, len(observed["argv"]))
        self.assertTrue(observed["argv"][0].endswith("/distribution/release/run_product_gate.py"))

    def test_pre_push_preserves_gate_failure(self) -> None:
        result, report = self.run_hook(gate_exit=7)
        self.assertEqual(7, result.returncode)
        self.assertEqual("mise", json.loads(report)["projectLauncher"])

    def test_rejected_pushes_do_not_start_the_gate(self) -> None:
        for options in ({"dirty": True}, {"other_head": True}):
            with self.subTest(options=options):
                result, report = self.run_hook(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", report)

    def test_deletion_does_not_start_the_gate(self) -> None:
        result, report = self.run_hook(deletion=True)
        self.assertEqual(0, result.returncode)
        self.assertEqual("", report)


if __name__ == "__main__":
    unittest.main()
