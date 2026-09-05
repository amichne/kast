#!/usr/bin/env python3
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import release_resource_observations as resources


class ResourceObservationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.owner = Path(self.temporary.name).resolve()
        self.runtime = self.owner / 'runtime'
        self.cache = self.runtime / 'intellij-caches'
        self.system = self.cache / 'identity' / 'system'
        self.system.mkdir(parents=True)
        self.installation = self.owner / 'installation'
        self.installation.mkdir()
        (self.installation / 'product.jar').write_bytes(b'1234567890')

    def sample(self, reader=None, **overrides):
        arguments = {'owner_root': self.owner, 'cache_root': self.cache,
                     'state_roots': (self.installation, self.runtime)}
        arguments.update(overrides)
        if reader is not None:
            arguments['process_reader'] = reader
        return resources.observe(resources.ResourceStage.AFTER_READ, **arguments)

    def mark(self, pid=1234):
        (self.system / '.pid').write_text(str(pid))

    def test_exact_owned_pid_observation_converts_kib_and_omits_command_payload(self):
        self.mark()
        calls = []
        command = f'/jdk/bin/java -Didea.system.path={self.system} -Dprivate.token=secret io.github.amichne.kast.indexer.KastIndexerMainKt'
        def reader(pid):
            calls.append(pid)
            return resources._PsOutput(0, f'{pid} 42 {command}\n'.encode())
        observed = self.sample(reader)
        self.assertEqual('observed', observed['status'])
        self.assertEqual(43008, observed['rssBytes'])
        self.assertEqual(1, observed['processCount'])
        self.assertEqual(14, observed['apparentStateBytes'])
        self.assertEqual([1234], calls)
        self.assertNotIn('secret', json.dumps(observed))
        self.assertNotIn(str(self.system), json.dumps(observed))

    def test_stopped_sample_preserves_disk_observation_without_invented_rss(self):
        observed = self.sample(lambda _: self.fail('No PID means no process query'))
        self.assertEqual('not-running', observed['status'])
        self.assertEqual('pid-marker-absent', observed['cause'])
        self.assertEqual(10, observed['apparentStateBytes'])
        self.assertNotIn('rssBytes', observed)

    def test_vanished_pid_is_distinct_from_unknown_process_failure(self):
        self.mark()
        self.assertEqual('process-not-listed', self.sample(lambda _: resources._PsOutput(1, b''))['cause'])
        self.assertEqual('ps-failed', self.sample(lambda _: resources._PsOutput(2, b''))['cause'])

    def test_pid_reuse_prefix_collision_and_duplicate_system_arguments_fail_closed(self):
        self.mark()
        for marker in (f'{self.system}-other', f'{self.system} -Didea.system.path=/unrelated'):
            command = f'1234 42 java -Didea.system.path={marker} private-source-payload\n'.encode()
            observed = self.sample(lambda _, output=command: resources._PsOutput(0, output))
            self.assertEqual('rejected', observed['status'])
            self.assertEqual('process-ownership-mismatch', observed['cause'])
            self.assertNotIn('private-source-payload', json.dumps(observed))

    def test_invalid_or_symlinked_pid_never_queries_a_process(self):
        for content in ('0', '-1', '1234 extra', '9' * 80, '1234' + ' ' * 40 + '9999'):
            with self.subTest(content=content):
                (self.system / '.pid').write_text(content)
                observed = self.sample(lambda _: self.fail('Invalid PID must not query ps'))
                self.assertEqual('pid-marker-invalid', observed['cause'])
        (self.system / '.pid').unlink()
        target = self.owner / 'external-pid'
        target.write_text('1234')
        (self.system / '.pid').symlink_to(target)
        self.assertEqual('pid-marker-invalid', self.sample(lambda _: self.fail('Symlink must not query ps'))['cause'])

    def test_disk_links_are_counted_without_following_their_payload(self):
        outside = self.owner / 'outside'
        outside.mkdir()
        (outside / 'unrelated').write_bytes(b'x' * 10000)
        link = self.installation / 'current'
        link.symlink_to(outside)
        observed = self.sample()
        self.assertEqual('not-running', observed['status'])
        self.assertEqual(10 + link.lstat().st_size, observed['apparentStateBytes'])
        self.assertEqual(1, observed['symlinkCount'])

    def test_selected_roots_cannot_escape_follow_symlinks_or_overlap(self):
        link = self.owner / 'aliased-state'
        link.symlink_to(self.runtime)
        for roots in ((self.owner.parent,), (link,), (self.runtime, self.cache)):
            with self.subTest(roots=roots):
                observed = self.sample(state_roots=roots)
                self.assertEqual('rejected', observed['status'])
                self.assertIn(observed['cause'], ('scope-invalid', 'state-roots-overlap'))

    def test_malformed_bounded_ps_results_never_publish_rss(self):
        self.mark()
        for output in (b'9999 42 java\n', b'1234 -1 java\n', b'garbage', b'x' * 65537):
            observed = self.sample(lambda _, value=output: resources._PsOutput(0, value))
            self.assertEqual('rejected', observed['status'])
            self.assertNotIn('rssBytes', observed)

    def test_real_ps_adapter_queries_only_this_test_process(self):
        observed = resources._run_ps(os.getpid())
        self.assertEqual(0, observed.returncode)
        self.assertLessEqual(len(observed.stdout), resources.MAX_PS_BYTES)
        self.assertEqual(str(os.getpid()).encode(), observed.stdout.split()[0])

    def test_marker_change_rejects_the_sample_instead_of_reusing_the_pid(self):
        self.mark()
        def reader(pid):
            (self.system / '.pid').write_text('9999')
            return resources._PsOutput(0, f'{pid} 42 java -Didea.system.path={self.system}'.encode())
        observed = self.sample(reader)
        self.assertEqual('pid-marker-changed', observed['cause'])
        self.assertNotIn('rssBytes', observed)

    def test_observation_limits_reject_without_partial_numerical_success(self):
        with mock.patch.object(resources, 'MAX_STATE_ENTRIES', 1):
            observed = self.sample()
        self.assertEqual('rejected', observed['status'])
        self.assertEqual('disk-observation-limit', observed['cause'])
        self.assertNotIn('apparentStateBytes', observed)
        with mock.patch.object(resources, 'MAX_CACHE_IDENTITIES', 0):
            self.assertEqual('cache-limit', self.sample()['cause'])

    def test_symlinked_system_directory_never_follows_or_queries_a_process(self):
        self.system.rmdir()
        outside = self.owner / 'outside-system'
        outside.mkdir()
        (outside / '.pid').write_text('1234')
        self.system.symlink_to(outside)
        observed = self.sample(lambda _: self.fail('Symlinked system directory must not query ps'))
        self.assertEqual('rejected', observed['status'])
        self.assertNotIn('rssBytes', observed)


if __name__ == '__main__':
    unittest.main()
