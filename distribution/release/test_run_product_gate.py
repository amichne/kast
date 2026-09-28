#!/usr/bin/env python3
"""Fresh release authority and exact version propagation without network or Gradle."""
import subprocess
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


if __name__ == "__main__":
    unittest.main()
