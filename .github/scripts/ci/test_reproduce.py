"""The reproduction command preserves the version, source identity, and evidence."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class ReproduceTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="kast reproduce test ")
        self.addCleanup(temporary.cleanup)
        self.parent = Path(temporary.name).resolve()
        self.repository = self.parent / "repository with spaces"
        self.repository.mkdir()
        self.scratch = self.parent / "scratch with spaces"
        self.scratch.mkdir()
        self.environment = {
            "PATH": os.environ["PATH"],
            "HOME": str(self.parent),
            "TMPDIR": str(self.scratch),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "KAST_RELEASE_JDK_25": str(self.parent / "jdk with spaces"),
            "KAST_ACCEPTANCE_IDEA_HOME": "/unused/local/idea",
        }
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Reproduction Test")
        self.git("config", "user.email", "reproduction@example.invalid")
        for name in (
            ".github/scripts/ci/reproduce.sh",
            ".github/scripts/ci/verify.sh",
            ".github/scripts/ci/build_execution.py",
            ".github/scripts/release/admit-source.sh",
        ):
            target = self.repository / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        (self.repository / ".gitignore").write_text("build/\n")
        builder = self.repository / ".github/scripts/release/build-assets.sh"
        builder.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            'printf "builder-arg=%s\\n" "$@"\n'
            'printf "builder-source=%s\\n" "$(git rev-parse HEAD)"\n'
            'printf "builder-cwd=%s\\n" "$PWD"\n'
            'printf "builder-java=%s\\n" "$JAVA_HOME"\n'
            'printf "builder-idea=%s\\n" "${KAST_ACCEPTANCE_IDEA_HOME-unset}"\n'
            'printf "builder-error-stream\\n" >&2\n'
            "cp native-metrics.json build/reports/native/build-output.json\n"
            'exit "$(cat builder-exit)"\n'
        )
        # Native metrics are an opaque JSON object in the real evidence adapter.
        (self.repository / "native-metrics.json").write_text(json.dumps({}))
        (self.repository / "builder-exit").write_text("0\n")
        self.git("add", ".")
        self.git("commit", "-m", "Candidate fixture")
        self.candidate = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("tag", "candidate-ref")
        (self.repository / "later-source").write_text("Excluded from the requested revision.\n")
        self.git("add", ".")
        self.git("commit", "-m", "Later source")
        self.head = self.git("rev-parse", "HEAD").stdout.strip()

    def git(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", "-C", str(self.repository), *arguments],
            env=self.environment,
            text=True,
            capture_output=True,
            check=True,
        )

    def reproduce(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(self.repository / ".github/scripts/ci/reproduce.sh"), *arguments],
            cwd=self.parent,
            env=self.environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def checkout(self, result: subprocess.CompletedProcess[str]) -> Path:
        prefix = "CI checkout and evidence retained at "
        paths = [
            line.removeprefix(prefix)
            for line in result.stdout.splitlines()
            if line.startswith(prefix)
        ]
        self.assertEqual(1, len(paths), result.stdout + result.stderr)
        return Path(paths[0])

    def test_explicit_version_reaches_builder_at_requested_detached_revision(self) -> None:
        result = self.reproduce("candidate-ref", "--version", "0.0.4039")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        checkout = self.checkout(result)
        self.assertEqual(
            self.candidate, self.git("-C", str(checkout), "rev-parse", "HEAD").stdout.strip()
        )
        self.assertFalse((checkout / "later-source").exists())
        detached = subprocess.run(
            ["git", "-C", str(checkout), "symbolic-ref", "-q", "HEAD"],
            env=self.environment,
            capture_output=True,
            check=False,
        )
        self.assertEqual(1, detached.returncode)
        self.assertIn(
            f"builder-arg=--version\nbuilder-arg=0.0.4039\nbuilder-arg=--source-revision\nbuilder-arg={self.candidate}\n",
            result.stdout,
        )
        for line in (
            f"builder-source={self.candidate}",
            f"builder-cwd={checkout}",
            f"builder-java={self.environment['KAST_RELEASE_JDK_25']}",
            "builder-idea=unset",
            "builder-error-stream",
        ):
            self.assertIn(line, result.stdout)
        log = (checkout.parent / "ci.log").read_text()
        self.assertIn("builder-error-stream", log)
        self.assertIn(f"release-candidate: built v0.0.4039 from {self.candidate}", log)
        report = json.loads((checkout / "build/reports/ci/build-execution.json").read_text())
        self.assertEqual("SUCCESS", report["outcome"])
        self.assertEqual(self.head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual("", self.git("status", "--porcelain").stdout)

    def test_builder_failure_keeps_checkout_log_and_failure_status(self) -> None:
        (self.repository / "builder-exit").write_text("7\n")
        self.git("add", "builder-exit")
        self.git("commit", "-m", "Failing builder fixture")
        revision = self.git("rev-parse", "HEAD").stdout.strip()
        result = self.reproduce(revision, "--version", "1.2.3")
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        checkout = self.checkout(result)
        self.assertIn("build-execution: PROCESS_FAILED", (checkout.parent / "ci.log").read_text())
        report = json.loads((checkout / "build/reports/ci/build-execution.json").read_text())
        self.assertEqual("PROCESS_FAILED", report["outcome"])
        self.assertEqual(7, report["exitCode"])
        self.assertTrue((checkout / ".git").exists())

    def test_bad_arguments_reject_before_worktree_or_evidence_creation(self) -> None:
        for arguments in (
            (),
            ("candidate-ref",),
            ("candidate-ref", "--version"),
            ("candidate-ref", "--version", ""),
            ("candidate-ref", "--version", "1.2"),
            ("candidate-ref", "--version", "v1.2.3"),
            ("candidate-ref", "--version", "1.2.3-rc1"),
            ("candidate-ref", "--version", "1.2.3 extra"),
            ("candidate-ref", "--other", "1.2.3"),
            ("candidate-ref", "--version", "1.2.3", "extra"),
        ):
            with self.subTest(arguments=arguments):
                result = self.reproduce(*arguments)
                self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                self.assertIn("--version", result.stderr)
                self.assertEqual([], list(self.scratch.iterdir()))
                self.assertEqual(
                    1, self.git("worktree", "list", "--porcelain").stdout.count("worktree ")
                )

    def test_unknown_revision_rejects_before_worktree_creation(self) -> None:
        result = self.reproduce("unknown-ref", "--version", "1.2.3")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("usage:", result.stderr)
        self.assertEqual([], list(self.scratch.iterdir()))


if __name__ == "__main__":
    unittest.main()
