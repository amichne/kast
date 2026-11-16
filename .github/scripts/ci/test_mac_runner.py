"""Only an online, idle, fully labelled local Mac is selected; every other observation uses hosted."""

import json
import unittest

from mac_runner import Reason, decide, select

LOCAL = ["self-hosted", "macOS", "ARM64", "kast-main"]


def runner(status="online", busy=False, labels=LOCAL):
    return {"name": "kast-main-mac", "status": status, "busy": busy, "labels": [{"name": name} for name in labels]}


class MacRunnerTest(unittest.TestCase):
    def test_idle_local_runner_is_selected_with_exact_labels(self):
        selection = select({"total_count": 2, "runners": [runner(status="offline"), runner()]})
        self.assertIs(Reason.IDLE_LOCAL_RUNNER, selection.reason)
        self.assertEqual(LOCAL, json.loads(selection.labels))

    def test_unavailable_local_runner_falls_back_to_hosted(self):
        for case in (
            runner(status="offline"),
            runner(busy=True),
            runner(labels=["self-hosted", "macOS", "ARM64"]),
            runner(labels=["self-hosted", "Linux", "X64", "kast-main"]),
        ):
            with self.subTest(case=case):
                selection = select({"runners": [case]})
                self.assertIs(Reason.NO_IDLE_LOCAL_RUNNER, selection.reason)
                self.assertEqual("macos-15", json.loads(selection.labels))
        self.assertIs(Reason.NO_IDLE_LOCAL_RUNNER, select({"runners": []}).reason)

    def test_malformed_status_rejects_to_hosted_instead_of_guessing(self):
        for document in (
            None,
            [],
            {"runners": None},
            {"runners": [runner(), {"status": "online", "busy": "false", "labels": []}]},
            {"runners": [{**runner(), "labels": [{"id": 1}]}]},
        ):
            with self.subTest(document=document):
                selection = select(document)
                self.assertIs(Reason.INVALID_STATUS, selection.reason)
                self.assertEqual("macos-15", json.loads(selection.labels))

    def test_missing_token_selects_hosted_without_a_request(self):
        selection = decide({"GITHUB_REPOSITORY": "amichne/kast"})
        self.assertIs(Reason.NO_STATUS_TOKEN, selection.reason)
        self.assertEqual("macos-15", json.loads(selection.labels))


if __name__ == "__main__":
    unittest.main()
