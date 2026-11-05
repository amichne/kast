"""Stale XML cannot authorize skipped or absent target tasks."""

import json
import tempfile
import unittest
import xml.etree.ElementTree as XML
from dataclasses import asdict
from pathlib import Path

import host_evidence as evidence
import host_target_validation as validation
import host_targets as hosts


class TargetValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.log = self.root / "gradle.log"

    def fixtures(self, line: str = "263") -> None:
        for task in validation.required_tasks():
            module, _, name = task[1:].rpartition(":")
            directory = (
                self.root
                / module.replace(":", "/")
                / "build/ide-targets"
                / line
                / "test-results"
                / name
            )
            directory.mkdir(parents=True)
            suite = XML.Element("testsuite", tests="1", failures="0", errors="0", skipped="0")
            XML.SubElement(suite, "testcase", name="actualFixture", classname="TargetFixture")
            XML.ElementTree(suite).write(directory / "TEST-target.xml", encoding="utf-8")
        self.log.write_text(
            "\n".join(f"> Task {task} FROM-CACHE" for task in sorted(validation.required_tasks()))
            + "\n"
        )

    def test_cached_reports_are_admitted_with_actual_task_results_and_exact_target_paths(
        self,
    ) -> None:
        self.fixtures()
        self.assertTrue(validation.executed_tasks(self.log))
        result = validation.inspect_reports(self.root, "263")
        self.assertIsInstance(result, tuple)
        assert isinstance(result, tuple)
        self.assertEqual(13, len(result))
        self.assertEqual(13, sum(report.tests for report in result))
        self.assertEqual(
            validation.Failure.MISSING_TEST_RESULTS, validation.inspect_reports(self.root, "262")
        )

    def test_no_source_skipped_and_missing_task_do_not_borrow_old_xml(self) -> None:
        self.fixtures()
        original = self.log.read_text()
        for suffix in ("NO-SOURCE", "SKIPPED", "FAILED"):
            self.log.write_text(original.replace("FROM-CACHE", suffix, 1))
            self.assertFalse(validation.executed_tasks(self.log))
        self.log.write_text("\n".join(original.splitlines()[1:]))
        self.assertFalse(validation.executed_tasks(self.log))

    def test_zero_tests_and_corrupt_counts_reject(self) -> None:
        self.fixtures()
        file = next(self.root.rglob("TEST-target.xml"))
        file.write_text('<testsuite tests="0" failures="0" errors="0" skipped="0"/>')
        self.assertEqual(
            validation.Failure.MISSING_TEST_RESULTS, validation.inspect_reports(self.root, "263")
        )
        file.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        self.assertEqual(
            validation.Failure.INVALID_TEST_RESULTS, validation.inspect_reports(self.root, "263")
        )

    def test_failed_and_skipped_cases_are_counted_without_pass_inflation(self) -> None:
        self.fixtures()
        file = next(self.root.rglob("TEST-target.xml"))
        suite = XML.Element("testsuite", tests="2", failures="1", errors="0", skipped="1")
        XML.SubElement(XML.SubElement(suite, "testcase", name="fails"), "failure")
        XML.SubElement(XML.SubElement(suite, "testcase", name="skips"), "skipped")
        XML.ElementTree(suite).write(file, encoding="utf-8")
        reports = validation.inspect_reports(self.root, "263")
        assert isinstance(reports, tuple)
        self.assertEqual(1, sum(report.failures for report in reports))
        self.assertEqual(1, sum(report.skipped for report in reports))

    def test_failed_first_attempt_invalidates_both_old_summaries_but_preserves_xml(self) -> None:
        self.fixtures()
        profile = hosts.Profile(
            1,
            "262",
            tuple(
                hosts.SdkTarget(line, f"{line}.123.45", f"{line}.123.45-IJ")
                for line in ("262", "263")
            ),
        )
        (self.root / "config").mkdir()
        (self.root / "config/intellij-targets.json").write_text(json.dumps(asdict(profile)))
        summaries: list[Path] = []
        for target in profile.targets:
            directory = self.root / "build/reports/host-targets" / target.intellijReleaseLine
            directory.mkdir(parents=True)
            for name in ("qualification.json", "target-receipt.json"):
                path = directory / name
                path.write_text("old success")
                summaries.append(path)
        xml = {path: path.read_bytes() for path in self.root.rglob("TEST-*.xml")}

        def fail(*arguments: str) -> Path:
            raise RuntimeError("observed failed Gradle invocation")

        with self.assertRaises(RuntimeError):
            validation.qualify(
                self.root,
                self.root / "release",
                evidence.BuildIdentity("0.0.3", "a" * 40, "b" * 40),
                fail,
            )
        self.assertTrue(all(not path.exists() for path in summaries))
        self.assertEqual(xml, {path: path.read_bytes() for path in xml})


if __name__ == "__main__":
    unittest.main()
