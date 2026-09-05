#!/usr/bin/env python3
"""The release matrix cannot manufacture proof from incomplete environment/JVM evidence."""
import importlib.util
import hashlib
import json
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
    def test_matrix_authority_retains_all_project_jvms_and_finite_failure(self):
        cases = acceptance.load_matrix()
        self.assertEqual({17, 21, 25}, {case.java for case in cases if case.expected_outcome == "ready"})
        self.assertTrue(any(case.expected_outcome == "jvm-rejected" for case in cases))

    def test_matrix_authority_rejects_duplicate_and_unknown_outcomes(self):
        document = json.loads(acceptance.MATRIX_FILE.read_text())
        with tempfile.TemporaryDirectory() as temporary:
            authority = Path(temporary) / "matrix.json"
            for mutation in ("duplicate", "unknown"):
                changed = json.loads(json.dumps(document))
                if mutation == "duplicate":
                    changed["cases"][-1] = changed["cases"][0]
                else:
                    changed["cases"][0]["expectedOutcome"] = "maybe-ready"
                authority.write_text(json.dumps(changed))
                with self.assertRaises(acceptance.AcceptanceFailure):
                    acceptance.load_matrix(authority)

    def test_import_observation_requires_exact_typed_client_and_project_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Path(temporary)
            log = host / "caches/identity/log/startup.log"
            log.parent.mkdir(parents=True)
            event = {"stage": "model-import", "outcome": "completed", "distribution": "7.6.4",
                     "clientJava": 25, "projectJava": 17, "clientHomeIdentity": "a" * 64, "projectHomeIdentity": "b" * 64}
            log.write_text("kast-indexer: Gradle import: " + json.dumps(event) + "\n")
            self.assertEqual(event, acceptance.completed_import_observation(host, "7.6.4", 17))
            event["projectJava"] = 25
            log.write_text("kast-indexer: Gradle import: " + json.dumps(event) + "\n")
            with self.assertRaises(acceptance.AcceptanceFailure):
                acceptance.completed_import_observation(host, "7.6.4", 17)

    def test_explicit_jdk_must_match_observed_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            (home / "bin").mkdir()
            java = home / "bin/java"
            java.write_text("#!/bin/sh\n")
            java.chmod(0o700)
            (home / "release").write_text('JAVA_VERSION="17.0.20"\n')
            admitted = acceptance.Jdk.parse(f"17:{home}")
            self.assertEqual(17, admitted.feature)
            self.assertEqual(hashlib.sha256((home / "release").read_bytes()).hexdigest(), admitted.release_sha256)
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
            (source / "build.gradle.kts").write_text('plugins { kotlin("jvm") version "2.3.10" apply false }\n')
            repository = root / "repo"
            wrapper = repository / "gradle/wrapper"
            wrapper.mkdir(parents=True)
            (wrapper / "gradle-wrapper.jar").write_bytes(b"wrapper-fixture")
            (repository / "gradlew").write_text("#!/bin/sh\n")
            destination = root / "workspace"
            with mock.patch.object(acceptance, "wrapper_checksum", return_value="a" * 64):
                acceptance.prepare_fixture(source, repository, destination, "7.6.4", acceptance.Jdk(17, Path("/jdk"), "b" * 64))
            build = (destination / "build.gradle.kts").read_text()
            self.assertIn('apply(plugin = "org.jetbrains.kotlin.jvm")', build)
            self.assertIn('System.getenv("AMBIENT_SECRET_LIKE_TOKEN") == null', build)
            self.assertIn('ProcessBuilder("kast-gradle-import-probe")', build)
            self.assertIn('System.getProperty("java.specification.version") == "17"', build)
            self.assertIn("gradle.gradleUserHomeDir.canonicalFile == admittedGradleHome", build)
            self.assertIn('System.getenv("GRADLE_USER_HOME") ?: File(System.getenv("HOME"), ".gradle")', build)
            self.assertIn("distributionSha256Sum=" + "a" * 64, (destination / "gradle/wrapper/gradle-wrapper.properties").read_text())


if __name__ == "__main__":
    unittest.main()
