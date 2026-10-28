"""Trusted main runs pass. Pull requests and incomplete job identities reject."""

import unittest

from self_hosted_guard import Admission, admit


def main_job():
    return {
        "GITHUB_REPOSITORY": "amichne/kast",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_EVENT_NAME": "push",
        "GITHUB_WORKFLOW_REF": "amichne/kast/.github/workflows/ci.yml@refs/heads/main",
        "GITHUB_JOB": "kotlin",
    }


class SelfHostedGuardTest(unittest.TestCase):
    def test_main_push_and_manual_main_dispatch_are_admitted(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                self.assertEqual(Admission.ALLOWED, admit({**main_job(), "GITHUB_EVENT_NAME": event}))

    def test_untrusted_events_and_other_identities_reject(self):
        cases = (
            ("GITHUB_EVENT_NAME", "pull_request", Admission.UNSUPPORTED_EVENT),
            ("GITHUB_EVENT_NAME", "pull_request_target", Admission.UNSUPPORTED_EVENT),
            ("GITHUB_EVENT_NAME", "merge_group", Admission.UNSUPPORTED_EVENT),
            ("GITHUB_REPOSITORY", "other/kast", Admission.WRONG_REPOSITORY),
            ("GITHUB_REF", "refs/pull/1/merge", Admission.WRONG_REF),
            ("GITHUB_REF", "refs/heads/feature", Admission.WRONG_REF),
            ("GITHUB_WORKFLOW_REF", "amichne/kast/.github/workflows/ci.yml@refs/pull/1/merge", Admission.WRONG_WORKFLOW),
            ("GITHUB_JOB", "source", Admission.WRONG_JOB),
        )
        for key, value, expected in cases:
            with self.subTest(key=key, value=value):
                self.assertEqual(expected, admit({**main_job(), key: value}))

    def test_missing_or_empty_fields_never_admit(self):
        for key in main_job():
            for value in (None, ""):
                with self.subTest(key=key, value=value):
                    environment = main_job()
                    if value is None:
                        del environment[key]
                    else:
                        environment[key] = value
                    self.assertNotEqual(Admission.ALLOWED, admit(environment))
