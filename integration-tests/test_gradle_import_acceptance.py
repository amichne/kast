#!/usr/bin/env python3
"""The release matrix cannot manufacture proof from incomplete environment/JVM evidence."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("gradle_import_acceptance", Path(__file__).with_name("gradle_import_acceptance.py"))
assert spec and spec.loader
acceptance = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = acceptance
spec.loader.exec_module(acceptance)


class GradleImportAcceptanceTest(unittest.TestCase):
    def test_explicit_jdk_must_match_observed_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            (home / "bin").mkdir()
            java = home / "bin/java"
            java.write_text("#!/bin/sh\n")
            java.chmod(0o700)
            (home / "release").write_text('JAVA_VERSION="17.0.20"\n')
            self.assertEqual(17, acceptance.Jdk.parse(f"17:{home}").feature)
            with self.assertRaises(acceptance.AcceptanceFailure):
                acceptance.Jdk.parse(f"21:{home}")

    def test_missing_report_and_empty_range_fail_closed(self):
        for document in ({}, {"bootstrap": {"gradleJvm": {"report": {"candidates": []}}}}):
            with self.assertRaises(acceptance.AcceptanceFailure):
                acceptance.selection_report(document)

    def test_generic_failure_is_not_compatible_jvm_rejection_proof(self):
        report = {"distribution": {"version": "7.6.4"}, "outcome": {"failure": "STARTUP_FAILED"}}
        with self.assertRaises(acceptance.AcceptanceFailure):
            acceptance.assert_selection(report, "7.6.4", 25, True)

    def test_selected_jvm_must_retain_explicit_authority(self):
        report = {"distribution": {"version": "7.6.4"},
                  "outcome": {"candidate": {"java": 17, "authority": "PLATFORM_RESOLVER"}}}
        with self.assertRaises(acceptance.AcceptanceFailure):
            acceptance.assert_selection(report, "7.6.4", 17, False)

    def test_unselected_value_leak_rejects_without_printing_it(self):
        completed = mock.Mock(stdout=acceptance.SECRET_VALUE, stderr="", returncode=0)
        with mock.patch.object(acceptance.subprocess, "run", return_value=completed):
            with self.assertRaises(acceptance.AcceptanceFailure) as raised:
                acceptance.command(Path("/kast"), Path("/workspace"), {}, ["start"], 1)
        self.assertNotIn(acceptance.SECRET_VALUE, str(raised.exception))

    def test_fixture_requires_environment_absence_and_explicit_executable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "build.gradle.kts").write_text('plugins { kotlin("jvm") version "2.3.10" }\n')
            repository = root / "repo"
            wrapper = repository / "gradle/wrapper"
            wrapper.mkdir(parents=True)
            (wrapper / "gradle-wrapper.jar").write_bytes(b"wrapper-fixture")
            (repository / "gradlew").write_text("#!/bin/sh\n")
            destination = root / "workspace"
            with mock.patch.object(acceptance, "wrapper_checksum", return_value="a" * 64):
                acceptance.prepare_fixture(source, repository, destination, "7.6.4", acceptance.Jdk(17, Path("/jdk")))
            build = (destination / "build.gradle.kts").read_text()
            self.assertIn('System.getenv("AMBIENT_SECRET_LIKE_TOKEN") == null', build)
            self.assertIn('ProcessBuilder("kast-gradle-import-probe")', build)
            self.assertIn('System.getProperty("java.specification.version") == "17"', build)
            self.assertIn("distributionSha256Sum=" + "a" * 64, (destination / "gradle/wrapper/gradle-wrapper.properties").read_text())


if __name__ == "__main__":
    unittest.main()
