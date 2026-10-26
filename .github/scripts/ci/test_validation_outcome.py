#!/usr/bin/env python3
"""Successful docs evidence cannot authorize product publication or cross producer identities."""

import json
import os
import sys
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import patch

import validation_outcome as outcome
from test_required_checks import docs_only_results
from validation_scope import ALL_CHECKS, DOCUMENTATION_CHECKS, SOURCE_CHECKS, Reason, Scope, Selection

REPOSITORY = "amichne/kast"
SOURCE = "b" * 40
TREE = "c" * 40
BASE = "a" * 40


def record():
    return outcome.ValidationRecord(
        1, outcome.Outcome.DOCS_VALIDATED, Scope.DOCUMENTATION, REPOSITORY, SOURCE, TREE, BASE, 456, 123, 1, "push"
    )


def producer():
    return {
        "repository": {"full_name": REPOSITORY},
        "path": outcome.WORKFLOW,
        "id": 456,
        "run_number": 123,
        "run_attempt": 1,
        "event": "push",
        "head_sha": SOURCE,
        "head_branch": "main",
        "status": "completed",
        "conclusion": "success",
    }


class ValidationOutcomeTest(unittest.TestCase):
    def test_source_success_has_a_distinct_non_product_outcome(self):
        document = asdict(record()) | {"outcome": "SOURCE_VALIDATED", "scope": "SOURCE"}
        decoded = outcome.decode(document)
        self.assertIsInstance(decoded, outcome.ValidationRecord)
        self.assertEqual(outcome.SourceValidated(decoded), outcome.admit(decoded, producer(), REPOSITORY, SOURCE, TREE))
        for changes in ({"scope": "FULL"}, {"outcome": "PRODUCT_VALIDATED"}, {"baseRevision": SOURCE}, {"baseRevision": ""}):
            self.assertEqual(outcome.Failure.INVALID_RECORD, outcome.decode(document | changes))

    def test_source_gate_seals_only_source_authority(self):
        needs = docs_only_results()
        needs["scope"]["outputs"].update(source="run", portable="run", documentation="skip", scope="SOURCE")
        needs["portable"]["result"] = "success"
        needs["source"]["result"] = "success"
        needs["documentation"]["result"] = "skipped"
        with patch.object(outcome, "select", return_value=Selection(SOURCE_CHECKS, Reason.CHANGED_INPUTS)):
            saved = outcome.record_gate(REPOSITORY, SOURCE, TREE, BASE, 456, 123, 1, "pull_request", needs)
        self.assertEqual(replace(record(), scope=Scope.SOURCE, outcome=outcome.Outcome.SOURCE_VALIDATED,
                                 event="pull_request"), saved)

    def test_closed_record_encodes_exact_no_publication_identity(self):
        document = json.loads(json.dumps(asdict(record())))
        self.assertEqual(
            {
                "schemaVersion": 1,
                "outcome": "DOCS_VALIDATED",
                "scope": "DOCUMENTATION",
                "repository": REPOSITORY,
                "sourceRevision": SOURCE,
                "sourceTree": TREE,
                "baseRevision": BASE,
                "runId": 456,
                "runNumber": 123,
                "runAttempt": 1,
                "event": "push",
            },
            document,
        )
        self.assertEqual(record(), outcome.decode(document))
        self.assertEqual(
            outcome.DocumentationValidated(record()), outcome.admit(record(), producer(), REPOSITORY, SOURCE, TREE)
        )

    def test_product_and_full_outcomes_retain_publication_obligation(self):
        for scope in (Scope.PRODUCT, Scope.FULL):
            value = replace(record(), scope=scope, outcome=outcome.Outcome.PRODUCT_VALIDATED)
            with self.subTest(scope=scope):
                self.assertEqual(value, outcome.decode(asdict(value)))
                self.assertEqual(
                    outcome.ProductValidated(value), outcome.admit(value, producer(), REPOSITORY, SOURCE, TREE)
                )

    def test_unknown_malformed_and_contradictory_records_reject(self):
        for changes in (
            {"schemaVersion": True},
            {"runId": True},
            {"runNumber": 0},
            {"runAttempt": 2.0},
            {"runNumber": 2147483648},
            {"outcome": "unknown"},
            {"scope": "unknown"},
            {"scope": "FULL"},
            {"outcome": "PRODUCT_VALIDATED"},
            {"sourceRevision": ""},
            {"sourceTree": "0" * 40},
            {"repository": "unknown"},
            {"baseRevision": ""},
            {"baseRevision": SOURCE},
            {"event": "workflow_dispatch"},
            {"extra": "field"},
        ):
            with self.subTest(changes=changes):
                self.assertEqual(outcome.Failure.INVALID_RECORD, outcome.decode(asdict(record()) | changes))
        for document in (None, [], "", {}, asdict(record()) | {"runAttempt": None}):
            self.assertEqual(outcome.Failure.INVALID_RECORD, outcome.decode(document))

    def test_wrong_run_attempt_source_tree_repository_or_event_never_admits(self):
        for changes in (
            {"id": 457},
            {"run_number": 124},
            {"run_attempt": 2},
            {"run_attempt": True},
            {"repository": {"full_name": "foreign/kast"}},
            {"repository": None},
            {"path": "foreign.yml"},
            {"head_sha": BASE},
            {"event": "pull_request"},
            {"head_branch": "feature"},
        ):
            with self.subTest(changes=changes):
                self.assertEqual(
                    outcome.Failure.WRONG_PRODUCER,
                    outcome.admit(record(), producer() | changes, REPOSITORY, SOURCE, TREE),
                )
        for changes in ({"status": "in_progress"}, {"conclusion": "failure"}, {"conclusion": "cancelled"}):
            self.assertEqual(
                outcome.Failure.PRODUCER_NOT_SUCCESSFUL,
                outcome.admit(record(), producer() | changes, REPOSITORY, SOURCE, TREE),
            )
        self.assertEqual(
            outcome.Failure.SOURCE_TREE_MISMATCH, outcome.admit(record(), producer(), REPOSITORY, SOURCE, BASE)
        )

    def test_only_complete_exact_selection_can_produce_an_outcome(self):
        with patch.object(outcome, "select", return_value=Selection(DOCUMENTATION_CHECKS, Reason.CHANGED_INPUTS)):
            self.assertEqual(
                record(), outcome.record_gate(REPOSITORY, SOURCE, TREE, BASE, 456, 123, 1, "push", docs_only_results())
            )
            for result in ("failure", "skipped", "cancelled"):
                needs = docs_only_results()
                needs["documentation"]["result"] = result
                self.assertEqual(
                    outcome.Failure.GATE_NOT_COMPLETE,
                    outcome.record_gate(REPOSITORY, SOURCE, TREE, BASE, 456, 123, 1, "push", needs),
                )
        # Missing history, mixed inputs, unknown events and checkout mismatch all select FULL.
        for reason in (
            Reason.DIFF_UNAVAILABLE,
            Reason.CHECKOUT_MISMATCH,
            Reason.FULL_EVENT,
            Reason.INVALID_REVISION,
            Reason.CHANGED_INPUTS,
        ):
            with (
                self.subTest(reason=reason),
                patch.object(outcome, "select", return_value=Selection(ALL_CHECKS, reason)),
            ):
                self.assertEqual(
                    outcome.Failure.SELECTION_MISMATCH,
                    outcome.record_gate(REPOSITORY, SOURCE, TREE, BASE, 456, 123, 1, "push", docs_only_results()),
                )

    def test_pr_record_uses_actual_checkout_head_instead_of_synthetic_merge_sha(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "validation.json"
            environment = {
                "GITHUB_REPOSITORY": REPOSITORY,
                "CHECKOUT_SHA": SOURCE,
                "GITHUB_SHA": "d" * 40,
                "BASE_SHA": BASE,
                "GITHUB_RUN_ID": "456",
                "GITHUB_RUN_NUMBER": "123",
                "GITHUB_RUN_ATTEMPT": "1",
                "GITHUB_EVENT_NAME": "pull_request",
                "CI_NEEDS": json.dumps(docs_only_results()),
            }
            with (
                patch.dict(os.environ, environment),
                patch.object(sys, "argv", ["validation_outcome.py", "--record", str(destination)]),
                patch.object(outcome.subprocess, "check_output", return_value=SOURCE + "\n" + TREE + "\n"),
                patch.object(
                    outcome, "select", return_value=Selection(DOCUMENTATION_CHECKS, Reason.CHANGED_INPUTS)
                ) as select,
            ):
                outcome.main()
            select.assert_called_once_with("pull_request", BASE, SOURCE, outcome.ROOT)
            saved = outcome.read(destination)
            self.assertEqual(replace(record(), event="pull_request"), saved)

    def test_checkout_mismatch_cannot_seal_even_a_complete_full_plan(self):
        needs = docs_only_results()
        needs["scope"]["outputs"].update(source="run", product="run", portable="run", gate_graph="run", scope="FULL")
        needs["source"]["result"] = needs["kotlin"]["result"] = needs["portable"]["result"] = "success"
        with patch.object(outcome, "select", return_value=Selection(ALL_CHECKS, Reason.CHECKOUT_MISMATCH)):
            self.assertEqual(
                outcome.Failure.SELECTION_MISMATCH,
                outcome.record_gate(REPOSITORY, SOURCE, TREE, BASE, 456, 123, 1, "push", needs),
            )

    def test_every_event_rejects_a_mismatched_checkout_before_record_creation(self):
        for event in ("push", "workflow_dispatch", "merge_group", "pull_request"):
            with self.subTest(event=event), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary) / "validation.json"
                with (
                    patch.dict(os.environ, {"CHECKOUT_SHA": SOURCE, "GITHUB_EVENT_NAME": event}),
                    patch.object(sys, "argv", ["validation_outcome.py", "--record", str(destination)]),
                    patch.object(outcome.subprocess, "check_output", return_value=BASE + "\n" + TREE + "\n"),
                    patch.object(outcome, "record_gate") as seal,
                    self.assertRaises(SystemExit) as rejected,
                ):
                    outcome.main()
                self.assertIn("CHECKOUT_MISMATCH", str(rejected.exception))
                seal.assert_not_called()
                self.assertFalse(destination.exists())

    def test_absent_oversized_symlink_duplicate_and_malformed_files_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "validation.json"
            self.assertEqual(outcome.Failure.INVALID_RECORD, outcome.read(path))
            for source in ("{", "null", " " * 4097, '{"outcome":"DOCS_VALIDATED","outcome":"PRODUCT_VALIDATED"}'):
                path.write_text(source)
                self.assertEqual(outcome.Failure.INVALID_RECORD, outcome.read(path))
            path.write_text(json.dumps(asdict(record())))
            self.assertEqual(record(), outcome.read(path))
            link = Path(temporary) / "link"
            link.symlink_to(path)
            self.assertEqual(outcome.Failure.INVALID_RECORD, outcome.read(link))


if __name__ == "__main__":
    unittest.main()
