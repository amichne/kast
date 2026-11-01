#!/usr/bin/env python3
"""Frozen corpus bytes and disposable-checkout admission constrain measurement effects."""

import hashlib
import json
import os
import unittest
from unittest.mock import patch

import rollout


class RolloutTest(unittest.TestCase):
    def test_cold_phase_disables_cache_transport_in_the_rollout_job_only(self):
        workflow = (rollout.ROOT / ".github/workflows/ci.yml").read_text()
        source = workflow.split("\n  source-work:\n", 1)[1].split("\n  kotlin:", 1)[0]
        measured = workflow.split("\n  rollout:\n", 1)[1].split("\n  rollout-feedback:", 1)[0]
        self.assertIn("cache-disabled: ${{ inputs.rollout == 'cold' }}", measured)
        self.assertIn("cache: ${{ inputs.rollout != 'cold' && 'pip' || '' }}", measured)
        self.assertNotIn("cache-disabled:", source)
        self.assertNotIn("inputs.rollout != 'cold'", source)

    def test_frozen_patch_bytes_ignore_repository_default_abbreviation(self):
        cases = rollout.corpus((rollout.ROOT / "config/ci/rollout-corpus.json").read_bytes())
        for case in cases:
            for abbreviation in (7, 40):
                with self.subTest(case=case.name, abbreviation=abbreviation):
                    raw = rollout.git(rollout.ROOT, "-c", f"core.abbrev={abbreviation}", *rollout.patch_arguments(case))
                    self.assertEqual(case.sha256, hashlib.sha256(raw).hexdigest())

    def test_exact_corpus_and_invalid_identities(self):
        raw = (rollout.ROOT / "config/ci/rollout-corpus.json").read_bytes()
        self.assertEqual(4, len(rollout.corpus(raw)))
        document = json.loads(raw)
        for key, value in (("before", "0"), ("sha256", "wrong"), ("paths", ["../outside"]), ("paths", [])):
            changed = [document[0] | {key: value}, *document[1:]]
            with self.subTest(key=key, value=value), self.assertRaisesRegex(ValueError, "INVALID_CORPUS"):
                rollout.corpus(json.dumps(changed).encode())
        with self.assertRaisesRegex(ValueError, "INVALID_CORPUS"):
            rollout.corpus(json.dumps(document + document[:1]).encode())

    def test_local_checkout_rejects_before_git_or_source_admission(self):
        case = rollout.corpus((rollout.ROOT / "config/ci/rollout-corpus.json").read_bytes())[0]
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}), patch.object(rollout, "git") as git, patch.object(rollout.source_validation, "source_identity") as identity:
            with self.assertRaisesRegex(ValueError, "DISPOSABLE_CI_CHECKOUT_REQUIRED"):
                rollout.prepare(rollout.ROOT, case)
        git.assert_not_called()
        identity.assert_not_called()


if __name__ == "__main__":
    unittest.main()
