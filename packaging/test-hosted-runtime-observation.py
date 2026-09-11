#!/usr/bin/env python3
"""Focused evidence for private runtime observation boundaries."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from hosted_runtime_observation import OwnedRuntimeObserver


class OwnedRuntimeObservationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.root.chmod(0o700)
        self.product = self.root / 'product'
        self.workspace = self.root / 'workspace'
        self.product.mkdir()
        self.workspace.mkdir()
        self.log = self.root / 'ide/log/idea.log'
        self.log.parent.mkdir(parents=True)
        self.log.write_text(self.resolution())
        self.observer = OwnedRuntimeObserver(self.root, self.product, self.workspace, Path('/bin/ps'))

    def resolution(self):
        return ('date INFO - #c.i.o.e.u.ExternalSystemUtil - External project [' + str(self.workspace)
                + '] resolution task executed in 12 ms.\n')

    def capture(self, generation=1, tree='10 1\n20 10\n30 20\n40 1\n'):
        result = subprocess.CompletedProcess([], 0, stdout=tree)
        with patch('hosted_runtime_observation.subprocess.run', return_value=result):
            return self.observer.capture(generation=generation, owned_pids=(10,))

    def test_baseline_and_delta_preserve_scope_and_process_limits(self):
        baseline = self.capture()
        self.assertEqual('observed', baseline['outcome'])
        self.assertEqual(1, baseline['imports']['resolutionCompleted'])
        self.assertEqual('generation-startup-baseline', baseline['imports']['scope'])
        self.assertEqual(2, baseline['processes']['descendantsPresent'])
        with self.log.open('a') as output:
            output.write(self.resolution())
        delta = self.capture()
        self.assertEqual(1, delta['imports']['resolutionCompleted'])
        self.assertEqual('since-previous-capture', delta['imports']['scope'])
        self.assertFalse(delta['imports']['absenceProven'])
        self.assertFalse(delta['processes']['absenceProven'])
        self.assertFalse(delta['artifacts']['absenceProven'])
        encoded = json.dumps(delta)
        self.assertNotIn(str(self.root), encoded)
        self.assertNotIn('External project', encoded)

    def test_actual_worker_artifact_detected_without_returning_payload(self):
        self.capture()
        receipts = self.product / 'state/workers'
        receipts.mkdir(parents=True)
        (receipts / 'private-id.json').write_text('{"secret":"not reportable"}')
        report = self.capture()
        self.assertEqual(['workers'], report['artifacts']['changedCategories'])
        self.assertEqual(1, report['artifacts']['categories']['workers']['files'])
        self.assertNotIn('secret', json.dumps(report))
        self.assertNotIn('private-id', json.dumps(report))

    def test_log_rotation_same_generation_is_unqualified(self):
        self.capture()
        self.log.rename(self.log.with_suffix('.old'))
        self.log.write_text(self.resolution())
        self.assertEqual({'outcome': 'unqualified', 'reason': 'log-identity-or-prefix-changed'}, self.capture())
        self.assertEqual('generation-startup-baseline', self.capture(2)['imports']['scope'])

    def test_log_rewrite_same_inode_is_unqualified(self):
        self.capture()
        self.log.write_text(self.resolution().replace('12 ms', '13 ms'))
        self.assertEqual('log-identity-or-prefix-changed', self.capture()['reason'])

    def test_symlink_and_bounds_never_become_zero(self):
        state = self.product / 'state'
        state.mkdir()
        (state / 'workers').symlink_to(self.workspace, target_is_directory=True)
        self.assertEqual('ownership-unproven', self.capture()['reason'])
        (state / 'workers').unlink()
        self.observer.MAX_LOG_BYTES = 2
        self.assertEqual('observation-bound-exceeded', self.capture()['reason'])

    def test_partial_log_line_is_carried_to_next_capture(self):
        self.capture()
        line = self.resolution()
        with self.log.open('a') as output:
            output.write(line[:20])
        self.assertEqual(0, self.capture()['imports']['resolutionCompleted'])
        with self.log.open('a') as output:
            output.write(line[20:])
        self.assertEqual(1, self.capture()['imports']['resolutionCompleted'])

    def test_process_parse_failure_is_unqualified(self):
        self.assertEqual('process-snapshot-unavailable', self.capture(tree='invalid\n')['reason'])


if __name__ == '__main__':
    unittest.main()
