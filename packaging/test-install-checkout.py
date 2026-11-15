"""Retired entry points and surviving Control admission bounds."""

import os
import re
import unittest
from pathlib import Path

import generate_bootstrap as bootstrap
import managed_release as release

ROOT = Path(__file__).resolve().parent.parent
ROOT_BUILD = ROOT / "build.gradle.kts"
CONTROL_LIMITS = (
    ROOT
    / "distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract"
    / "ControlDistributionLimits.kt"
)
INSTALLATION_LIFECYCLE = ROOT / "packaging/installation-lifecycle.py"


class InstallerRetirementTest(unittest.TestCase):
    def test_obsolete_entry_points_cannot_be_reintroduced(self) -> None:
        for name in ("install-legacy.sh", "install-local.sh", "install-checkout.sh"):
            with self.subTest(name=name):
                self.assertFalse(os.path.lexists(ROOT / "packaging" / name))

    def test_installed_support_uses_the_root_bootstrap(self) -> None:
        files = dict(bootstrap.support_files())
        self.assertEqual(ROOT / "install.sh", files["install.sh"])
        self.assertFalse(
            {"install-legacy.sh", "install-local.sh", "install-checkout.sh"} & files.keys()
        )
        self.assertTrue(files["install.sh"].is_file())


class ControlDistributionLimitTest(unittest.TestCase):
    def test_admission_build_and_archive_entry_limits_are_identical(self) -> None:
        sources = (
            (ROOT_BUILD, r"controlDistributionMaximumEntries\s*=\s*([0-9_]+)"),
            (CONTROL_LIMITS, r"maximumEntryCount:\s*Int\s*=\s*([0-9_]+)"),
        )
        self.assertEqual(16_384, release.MAXIMUM_ENTRIES)
        for path, pattern in sources:
            match = re.search(pattern, path.read_text())
            self.assertIsNotNone(match, path)
            assert match is not None
            self.assertEqual(16_384, int(match.group(1).replace("_", "")))

    def test_admission_and_kotlin_payload_byte_limits_are_identical(self) -> None:
        self.assertEqual(1_073_741_824, release.MAXIMUM_PAYLOAD_BYTES)
        self.assertRegex(
            CONTROL_LIMITS.read_text(),
            r"maximumPayloadBytes:\s*Long\s*=\s*1_073_741_824L",
        )

    def test_kotlin_and_installed_lifecycle_entry_limits_are_identical(self) -> None:
        sources = (
            (CONTROL_LIMITS, r"maximumTraversedEntries:\s*Int\s*=\s*([0-9_]+)"),
            (INSTALLATION_LIFECYCLE, r"CONTROL_MAXIMUM_ENTRIES\s*=\s*([0-9_]+)"),
        )
        for path, pattern in sources:
            match = re.search(pattern, path.read_text())
            self.assertIsNotNone(match, path)
            assert match is not None
            self.assertEqual(32_768, int(match.group(1).replace("_", "")))

    def test_kotlin_and_installed_lifecycle_manifest_limits_are_identical(self) -> None:
        sources = (
            (
                CONTROL_LIMITS,
                r"maximumManifestBytes:\s*Int\s*=\s*64\s*\*\s*1_024\s*\*\s*1_024",
            ),
            (INSTALLATION_LIFECYCLE, r"CONTROL_MANIFEST_MAXIMUM_BYTES\s*=\s*67108864"),
        )
        for path, pattern in sources:
            self.assertRegex(path.read_text(), pattern, str(path))


if __name__ == "__main__":
    _ = unittest.main()
