#!/usr/bin/env python3
"""A skipped dependency is not evidence that a selected check succeeded."""

import io
import json
import os
import unittest
from contextlib import redirect_stdout
from copy import deepcopy
from unittest.mock import patch

from required_checks import CompletedSourceChecks, Gate, Verdict, admit_checks, inspect, main


def docs_only_results():
    return {
        "scope": {
            "result": "success",
            "outputs": {
                "source": "skip",
                "product": "skip",
                "portable": "skip",
                "documentation": "run",
                "gate_graph": "skip",
                "scope": "DOCUMENTATION",
            },
        },
        "source": {"result": "skipped"},
        "kotlin": {"result": "skipped"},
        "portable": {"result": "skipped"},
        "documentation": {"result": "success"},
    }


class RequiredChecksTest(unittest.TestCase):
    def test_source_feedback_cannot_complete_candidate_qualification(self):
        needs = docs_only_results()
        needs["scope"]["outputs"] = dict.fromkeys(("source", "product", "portable", "documentation", "gate_graph"), "run")
        needs["scope"]["outputs"]["scope"] = "FULL"
        needs["source"]["result"] = "success"
        needs["portable"]["result"] = "success"
        del needs["kotlin"]
        self.assertIsInstance(admit_checks(needs, Gate.SOURCE_FEEDBACK), CompletedSourceChecks)
        self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))
        for job in ("source", "documentation", "portable"):
            failed = deepcopy(needs)
            failed[job]["result"] = "skipped"
            self.assertEqual(Verdict.CHECK_FAILED, inspect(failed, Gate.SOURCE_FEEDBACK))

    def test_source_feedback_requires_source_success_without_a_candidate(self):
        needs = docs_only_results()
        needs["scope"]["outputs"].update(source="run", portable="run", documentation="skip", scope="SOURCE")
        needs["portable"]["result"] = "success"
        needs["documentation"]["result"] = "skipped"
        needs["source"] = {"result": "success"}
        self.assertEqual(Verdict.COMPLETE, inspect(needs))
        for result in ("skipped", "failure", "cancelled"):
            needs["source"]["result"] = result
            self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))

    def test_only_explicitly_excluded_jobs_may_skip(self):
        self.assertEqual(Verdict.COMPLETE, inspect(docs_only_results()))
        for result in ("skipped", "failure", "cancelled", ""):
            needs = docs_only_results()
            needs["documentation"]["result"] = result
            with self.subTest(result=result):
                self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))

    def test_full_plan_requires_all_jobs_to_succeed(self):
        needs = docs_only_results()
        needs["scope"]["outputs"] = dict.fromkeys(("source", "product", "portable", "documentation", "gate_graph"), "run")
        needs["scope"]["outputs"]["scope"] = "FULL"
        needs["kotlin"]["result"] = "skipped"
        self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))
        needs["source"]["result"] = needs["kotlin"]["result"] = needs["portable"]["result"] = "success"
        self.assertEqual(Verdict.COMPLETE, inspect(needs))

    def test_selector_failure_is_not_hidden_by_skipped_dependents(self):
        for result in ("failure", "cancelled", "skipped", ""):
            needs = docs_only_results()
            needs["scope"]["result"] = result
            with self.subTest(result=result):
                self.assertEqual(Verdict.SELECTOR_FAILED, inspect(needs))

    def test_unknown_or_missing_decisions_do_not_authorize_skips(self):
        for decision in (None, "", "false", "unknown"):
            needs = docs_only_results()
            needs["scope"]["outputs"]["product"] = decision
            with self.subTest(decision=decision):
                self.assertEqual(Verdict.INVALID_SELECTION, inspect(needs))
        needs = docs_only_results()
        del needs["scope"]["outputs"]
        self.assertEqual(Verdict.INVALID_SELECTION, inspect(needs))

    def test_incomplete_or_contradictory_plans_reject(self):
        for changes in (
            {"gate_graph": None},
            {"product": "run"},
            {"portable": "run"},
            {"scope": "FULL"},
            {"scope": "unknown"},
            {"scope": None},
            {"documentation": "skip"},
            {"gate_graph": "run"},
        ):
            needs = docs_only_results()
            needs["scope"]["outputs"].update(changes)
            with self.subTest(changes=changes):
                self.assertEqual(Verdict.INVALID_SELECTION, inspect(needs))

    def test_product_plan_retains_the_original_product_proofs(self):
        needs = docs_only_results()
        needs["scope"]["outputs"].update(source="run", product="run", portable="run", documentation="skip", scope="PRODUCT")
        needs["source"]["result"] = needs["kotlin"]["result"] = needs["portable"]["result"] = "success"
        needs["documentation"]["result"] = "skipped"
        self.assertEqual(Verdict.COMPLETE, inspect(needs))

    def test_unselected_jobs_must_really_be_skipped(self):
        for result in ("success", "failure", "cancelled", ""):
            needs = docs_only_results()
            needs["kotlin"]["result"] = result
            with self.subTest(result=result):
                self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))

    def test_missing_job_result_fails(self):
        needs = docs_only_results()
        del needs["documentation"]
        self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))

    def test_cli_reports_and_fails_malformed_or_unsuccessful_results(self):
        failed = deepcopy(docs_only_results())
        failed["documentation"]["result"] = "failure"
        for payload, code in (
            (json.dumps(docs_only_results()), 0),
            (json.dumps(failed), 1),
            ("{", 1),
            ("null", 1),
            ('{"scope":null}', 1),
        ):
            with (
                self.subTest(payload=payload),
                patch.dict(os.environ, {"CI_NEEDS": payload}),
                patch("sys.argv", ["required_checks.py"]),
                redirect_stdout(io.StringIO()),
                self.assertRaises(SystemExit) as failure,
            ):
                main()
            self.assertEqual(code, failure.exception.code)


if __name__ == "__main__":
    unittest.main()
