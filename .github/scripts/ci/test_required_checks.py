#!/usr/bin/env python3
"""A skipped dependency is not evidence that a selected check succeeded."""
from contextlib import redirect_stdout
from copy import deepcopy
import io
import json
import os
import unittest
from unittest.mock import patch

from required_checks import Verdict, inspect, main


def docs_only_results():
    return {
        "scope": {"result": "success", "outputs": {
            "product": "run", "portable": "run", "documentation": "run", "gate_graph": "skip",
        }},
        "kotlin": {"result": "success"},
        "portable": {"result": "success"},
        "documentation": {"result": "success"},
    }


class RequiredChecksTest(unittest.TestCase):
    def test_only_explicitly_excluded_jobs_may_skip(self):
        self.assertEqual(Verdict.COMPLETE, inspect(docs_only_results()))
        for result in ("skipped", "failure", "cancelled", ""):
            needs = docs_only_results()
            needs["documentation"]["result"] = result
            with self.subTest(result=result):
                self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))

    def test_full_plan_requires_all_jobs_to_succeed(self):
        needs = docs_only_results()
        needs["scope"]["outputs"] = dict.fromkeys(("product", "portable", "documentation", "gate_graph"), "run")
        needs["kotlin"]["result"] = "skipped"
        self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))
        needs["kotlin"]["result"] = needs["portable"]["result"] = "success"
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
        for changes in ({"gate_graph": None}, {"product": "skip"}, {"portable": "skip"}):
            needs = docs_only_results()
            needs["scope"]["outputs"].update(changes)
            with self.subTest(changes=changes):
                self.assertEqual(Verdict.INVALID_SELECTION, inspect(needs))

    def test_missing_job_result_fails(self):
        needs = docs_only_results()
        del needs["documentation"]
        self.assertEqual(Verdict.CHECK_FAILED, inspect(needs))

    def test_cli_reports_and_fails_malformed_or_unsuccessful_results(self):
        failed = deepcopy(docs_only_results())
        failed["documentation"]["result"] = "failure"
        for payload, code in ((json.dumps(docs_only_results()), 0), (json.dumps(failed), 1),
                              ("{", 1), ("null", 1), ('{"scope":null}', 1)):
            with self.subTest(payload=payload), patch.dict(os.environ, {"CI_NEEDS": payload}), \
                    redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as failure:
                main()
            self.assertEqual(code, failure.exception.code)


if __name__ == "__main__":
    unittest.main()
