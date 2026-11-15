"""Canonical bootstrap ingress and complete-product acceptance boundaries.

Archive activation and interrupted replacement belong to test-managed-build-record.py;
this suite checks the actual public shell and the native acceptance command contract.
"""

import subprocess
import sys
import unittest
from pathlib import Path

from installer_fixture import InstallerFixture, admitted_tools

ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "install.sh"
ACCEPTANCE = Path(__file__).with_name("run-installed-product.py")
DOCUMENTS = (
    ROOT / "README.md",
    ROOT / "docs/public/start.mdx",
    ROOT / "docs/public/reference/installation.mdx",
    ROOT / "docs/public/reference/compatibility.mdx",
)
CANONICAL_PREFIX = '/bin/bash -c "$(curl -fsSL '


class InstallerEntrypointTest(unittest.TestCase):
    def test_documented_remote_invocations_use_bash_c(self) -> None:
        for document in DOCUMENTS:
            text = document.read_text()
            with self.subTest(document=document.relative_to(ROOT)):
                self.assertNotIn("| bash", text)
                self.assertNotIn("| " + "\\" + "\n  bash", text)
                for line in text.splitlines():
                    if "raw.githubusercontent.com/amichne/kast" in line:
                        self.assertTrue(line.lstrip(" \t").startswith(CANONICAL_PREFIX), line)

    def test_double_dash_delivers_help_to_downloaded_script(self) -> None:
        with InstallerFixture(admitted_tools()) as fixture:
            result = subprocess.run(
                [str(fixture.tools["bash"]), "-c", INSTALLER.read_text(), "--", "--help"],
                cwd=fixture.root / "workspace",
                env=fixture.environment,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("one complete private-runtime product", result.stdout)
            self.assertIn("No Python, uv, or IDEA", result.stdout)
            self.assertFalse((fixture.root / "home/.local").exists())
            fixture.mark_passed()

    def test_obsolete_component_options_reject_before_effects(self) -> None:
        for option in ("--idea-home", "--control-only", "--host-only", "--stage-only", "uninstall"):
            with self.subTest(option=option), InstallerFixture(admitted_tools()) as fixture:
                sentinel = fixture.root / "home/foreign"
                _ = sentinel.write_bytes(b"protected")
                result = subprocess.run(
                    [str(fixture.tools["bash"]), str(INSTALLER), option],
                    env=fixture.environment,
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("unsupported product installation option", result.stderr)
                self.assertEqual(b"protected", sentinel.read_bytes())
                self.assertFalse((fixture.root / "home/.local").exists())
                self.assertEqual([], list((fixture.root / "tmp").iterdir()))
                fixture.mark_passed()

    def test_acceptance_requires_complete_candidate_baseline_and_private_inputs(self) -> None:
        result = subprocess.run(
            [sys.executable, str(ACCEPTANCE), "--help"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        for option in (
            "--candidate",
            "--baseline",
            "--private-python-archive",
            "--private-dependency",
            "--target",
            "--evidence",
            "--receipt",
        ):
            self.assertIn(option, result.stdout)

    def test_absent_candidate_rejects_without_receipt_or_evidence(self) -> None:
        with InstallerFixture(admitted_tools()) as fixture:
            receipt = fixture.root / "receipt.json"
            evidence = fixture.root / "evidence"
            result = subprocess.run(
                [
                    sys.executable,
                    str(ACCEPTANCE),
                    "--candidate",
                    str(fixture.root / "absent-candidate"),
                    "--baseline",
                    str(fixture.root / "absent-baseline"),
                    "--private-python-archive",
                    str(fixture.root / "absent-python"),
                    "--private-dependency",
                    str(fixture.root / "absent-dependency"),
                    "--target",
                    "macos-aarch64",
                    "--evidence",
                    str(evidence),
                    "--receipt",
                    str(receipt),
                ],
                env=fixture.environment,
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("installed-product acceptance rejected", result.stderr)
            self.assertFalse(receipt.exists())
            self.assertFalse(evidence.exists())
            fixture.mark_passed()

    def test_existing_acceptance_receipt_is_never_overwritten(self) -> None:
        with InstallerFixture(admitted_tools()) as fixture:
            receipt = fixture.root / "receipt.json"
            _ = receipt.write_bytes(b"foreign receipt")
            evidence = fixture.root / "evidence"
            result = subprocess.run(
                [
                    sys.executable,
                    str(ACCEPTANCE),
                    "--candidate",
                    str(fixture.root / "absent-candidate"),
                    "--baseline",
                    str(fixture.root / "absent-baseline"),
                    "--private-python-archive",
                    str(fixture.root / "absent-python"),
                    "--private-dependency",
                    str(fixture.root / "absent-dependency"),
                    "--target",
                    "macos-aarch64",
                    "--evidence",
                    str(evidence),
                    "--receipt",
                    str(receipt),
                ],
                env=fixture.environment,
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("receipt already exists", result.stderr)
            self.assertEqual(b"foreign receipt", receipt.read_bytes())
            self.assertFalse(evidence.exists())
            fixture.mark_passed()


if __name__ == "__main__":
    _ = unittest.main()
