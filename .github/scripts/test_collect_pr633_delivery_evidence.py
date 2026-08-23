#!/usr/bin/env python3

from __future__ import annotations

import argparse
import unittest

import collect_pr633_delivery_evidence as collector


class FakeGitHub:
    repository = "amichne/kast"

    def pull_request(self, number: int) -> dict:
        return {"base": {"ref": "main"}, "head": {"sha": "a" * 40}}

    def check_runs(self, head_sha: str) -> list[dict]:
        return [
            {
                "name": "pr633-merge-candidate",
                "status": "completed",
                "conclusion": "success",
                "head_sha": head_sha,
                "id": 17,
            }
        ]


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

    def test_exact_head_evidence_rejects_a_different_expected_head(self) -> None:
        args = argparse.Namespace(
            pull_request=633,
            expected_base="main",
            expected_head="b" * 40,
            check_name="pr633-merge-candidate",
        )

        with self.assertRaises(collector.EvidenceFailure):
            collector.exact_head_ci(FakeGitHub(), args)


if __name__ == "__main__":
    unittest.main()
