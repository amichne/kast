import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

import release_eligibility as eligibility


class ReleaseEligibilityTest(unittest.TestCase):
    sha = 'a' * 40
    repository = 'amichne/kast'

    def setUp(self):
        self.run = dict(id=42, head_sha=self.sha, head_branch='main', event='push',
                        path='.github/workflows/ci.yml', status='completed', conclusion='success',
                        head_repository={'full_name': self.repository})
        self.artifact = dict(id=9, name=f'release-candidate-{self.sha}', expired=False,
                             workflow_run={'id': 42, 'head_sha': self.sha})

    def admit(self, runs=None, artifacts=None):
        return eligibility.admit(runs if runs is not None else [self.run],
                                 artifacts if artifacts is not None else [self.artifact],
                                 self.repository, self.sha)

    def test_success_binds_exact_run_and_artifact(self):
        result = self.admit()
        self.assertEqual((42, self.artifact['name']), (result.run_id, result.artifact_name))

    def test_pending_latest_run_does_not_fall_back_to_old_success(self):
        pending = {**self.run, 'id': 43, 'status': 'in_progress', 'conclusion': None}
        with self.assertRaises(eligibility.Rejected):
            self.admit(runs=[self.run, pending])

    def test_wrong_source_or_untrusted_run_is_rejected(self):
        for field, value in [('head_sha', 'b'*40), ('head_branch', 'feature'),
                             ('event', 'pull_request'), ('path', '.github/workflows/other.yml'),
                             ('head_repository', {'full_name': 'someone/kast'}),
                             ('conclusion', 'failure')]:
            with self.subTest(field=field), self.assertRaises(eligibility.Rejected):
                self.admit(runs=[{**self.run, field: value}])

    def test_missing_expired_ambiguous_or_wrong_run_artifacts_are_rejected(self):
        for artifacts in [[], [self.artifact, self.artifact],
                          [{**self.artifact, 'expired': True}],
                          [{**self.artifact, 'name': 'release-gate-' + self.sha}],
                          [{**self.artifact, 'workflow_run': {'id': 41, 'head_sha': self.sha}}]]:
            with self.subTest(artifacts=artifacts), self.assertRaises(eligibility.Rejected):
                self.admit(artifacts=artifacts)

    def test_missing_run_is_rejected(self):
        with self.assertRaises(eligibility.Rejected):
            self.admit(runs=[])

    def test_preflight_rejects_before_artifact_download(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / 'distribution/release').mkdir(parents=True)
            (root / 'distribution/release/candidate-version.txt').write_text('0.33.0\n')
            with patch.object(eligibility, 'api') as api:
                with self.assertRaises(eligibility.Rejected) as rejected:
                    eligibility.observe(self.repository, self.sha, '0.34.0', root)
                self.assertEqual(eligibility.Cause.VERSION_MISMATCH, rejected.exception.cause)
                api.assert_not_called()
            with patch.object(eligibility, 'api', return_value=[{'object': {'sha': 'b'*40}}]) as api:
                with self.assertRaises(eligibility.Rejected) as rejected:
                    eligibility.observe(self.repository, self.sha, '0.33.0', root)
                self.assertEqual(eligibility.Cause.SOURCE_MOVED, rejected.exception.cause)
                self.assertEqual(1, api.call_count)
            for release_pages, tag_pages in [([[{'tag_name': 'v0.33.0'}]], [[]]), ([[]], [[{'name': 'v0.33.0'}]])]:
                with patch.object(eligibility, 'api', side_effect=[[{'object': {'sha': self.sha}}], release_pages, tag_pages]) as api:
                    with self.assertRaises(eligibility.Rejected) as rejected:
                        eligibility.observe(self.repository, self.sha, '0.33.0', root)
                    self.assertEqual(eligibility.Cause.RELEASE_EXISTS, rejected.exception.cause)
                    self.assertEqual(3, api.call_count)

    def test_live_observation_admits_paginated_ci_and_artifacts(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / 'distribution/release').mkdir(parents=True)
            (root / 'distribution/release/candidate-version.txt').write_text('0.33.0\n')
            responses = [[{'object': {'sha': self.sha}}], [[]], [[]],
                         [{'workflow_runs': []}, {'workflow_runs': [self.run]}],
                         [{'artifacts': []}, {'artifacts': [self.artifact]}]]
            with patch.object(eligibility, 'api', side_effect=responses):
                self.assertEqual(42, eligibility.observe(self.repository, self.sha, '0.33.0', root).run_id)


if __name__ == '__main__':
    unittest.main()
