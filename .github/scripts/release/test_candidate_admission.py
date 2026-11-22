"""Candidate admission rejects each invalid input with its own failure."""

from __future__ import annotations

import unittest

from candidate_admission import Admitted, Failure, Rejected, admit, main


class CandidateAdmissionTest(unittest.TestCase):
    def test_admits_a_numeric_version_for_a_known_target(self) -> None:
        self.assertEqual(
            Admitted("0.0.7", "macos-aarch64"), admit("0.0.7", "", "macos-aarch64", "")
        )
        self.assertEqual(
            Admitted("1.2.3", "linux-x86_64"),
            admit("1.2.3", "", "linux-x86_64", "linux-x86_64"),
        )

    def test_rejects_each_invalid_input_with_its_failure(self) -> None:
        cases = (
            (("1.2", "", "macos-aarch64", ""), Failure.INVALID_VERSION),
            (("1.2.3-rc1", "", "macos-aarch64", ""), Failure.INVALID_VERSION),
            (("", "", "macos-aarch64", ""), Failure.INVALID_VERSION),
            (("1.2.3", "a" * 40, "macos-aarch64", ""), Failure.RETIRED_ACTION_REVISION),
            (("1.2.3", "", "linux-aarch64", ""), Failure.UNKNOWN_TARGET),
            (("1.2.3", "", "", ""), Failure.UNKNOWN_TARGET),
            (("1.2.3", "", "linux-x86_64", "macos-aarch64"), Failure.RELEASE_TARGET_MISMATCH),
        )
        for arguments, failure in cases:
            with self.subTest(arguments=arguments):
                outcome = admit(*arguments)
                self.assertIsInstance(outcome, Rejected)
                assert isinstance(outcome, Rejected)
                self.assertEqual(failure, outcome.failure)

    def test_command_exit_status_follows_the_outcome(self) -> None:
        admitted = ["--version", "0.0.0", "--action-revision", "", "--target", "macos-aarch64"]
        self.assertEqual(0, main(admitted))
        self.assertEqual(0, main([*admitted, "--release-target", ""]))
        self.assertEqual(1, main([*admitted, "--release-target", "linux-x86_64"]))


if __name__ == "__main__":
    unittest.main()
