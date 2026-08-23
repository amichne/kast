#!/usr/bin/env python3

from __future__ import annotations

import argparse
import unittest

import collect_pr633_delivery_evidence as collector


class FakeGitHub:
    repository = "amichne/kast"

    def __init__(
        self,
        *,
        pull_request: dict | None = None,
        check_run: dict | None = None,
    ) -> None:
        self._pull_request = pull_request or {
            "base": {"ref": "main"},
            "head": {"sha": "a" * 40},
            "state": "open",
            "merged": False,
        }
        self._check_run = check_run or {
            "name": "pr633-merge-candidate",
            "status": "completed",
            "conclusion": "success",
            "head_sha": "a" * 40,
            "id": 17,
        }

    def pull_request(self, number: int) -> dict:
        return self._pull_request

    def check_runs(self, head_sha: str) -> list[dict]:
        return [self._check_run]


class CollectPr633DeliveryEvidenceTest(unittest.TestCase):
    def test_exact_head_evidence_binds_check_to_pull_request_head(self) -> None:
        args = argparse.Namespace(
            pull_request=633,
            expected_base="main",
            expected_head="a" * 40,
            check_name="pr633-merge-candidate",
        )

        result = collector.exact_head_ci(FakeGitHub(), args)

        self.assertEqual("a" * 40, result["headSha"])
        self.assertEqual("success", result["facts"]["checkConclusion"])
        self.assertEqual("17", result["facts"]["checkRunId"])

    def test_exact_head_evidence_rejects_a_different_expected_head(self) -> None:
        args = argparse.Namespace(
            pull_request=633,
            expected_base="main",
            expected_head="b" * 40,
            check_name="pr633-merge-candidate",
        )

        with self.assertRaises(collector.EvidenceFailure):
            collector.exact_head_ci(FakeGitHub(), args)

    def test_exact_head_evidence_requires_an_explicit_expected_head(self) -> None:
        args = argparse.Namespace(
            pull_request=633,
            expected_base="main",
            expected_head=None,
            check_name="pr633-merge-candidate",
        )

        with self.assertRaises(collector.EvidenceFailure):
            collector.exact_head_ci(FakeGitHub(), args)

    def test_exact_head_evidence_rejects_closed_or_merged_pull_request(self) -> None:
        args = argparse.Namespace(
            pull_request=633,
            expected_base="main",
            expected_head="a" * 40,
            check_name="pr633-merge-candidate",
        )
        for state, merged in (("closed", False), ("open", True)):
            with self.subTest(state=state, merged=merged):
                pull_request = {
                    "base": {"ref": "main"},
                    "head": {"sha": "a" * 40},
                    "state": state,
                    "merged": merged,
                }
                with self.assertRaises(collector.EvidenceFailure):
                    collector.exact_head_ci(FakeGitHub(pull_request=pull_request), args)

    def test_exact_head_evidence_requires_check_head_sha(self) -> None:
        args = argparse.Namespace(
            pull_request=633,
            expected_base="main",
            expected_head="a" * 40,
            check_name="pr633-merge-candidate",
        )
        check_run = {
            "name": "pr633-merge-candidate",
            "status": "completed",
            "conclusion": "success",
            "id": 17,
        }

        with self.assertRaises(collector.EvidenceFailure):
            collector.exact_head_ci(FakeGitHub(check_run=check_run), args)

    def test_exact_head_evidence_requires_positive_check_run_id(self) -> None:
        args = argparse.Namespace(
            pull_request=633,
            expected_base="main",
            expected_head="a" * 40,
            check_name="pr633-merge-candidate",
        )
        check_run = {
            "name": "pr633-merge-candidate",
            "status": "completed",
            "conclusion": "success",
            "head_sha": "a" * 40,
            "id": 0,
        }

        with self.assertRaises(collector.EvidenceFailure):
            collector.exact_head_ci(FakeGitHub(check_run=check_run), args)


if __name__ == "__main__":
    unittest.main()
