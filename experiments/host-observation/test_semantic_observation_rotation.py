"""Bounded native log collection; one owned filesystem represents a rollover."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import reproduce_semantic_queries as replay


class ObservationRotationTest(unittest.TestCase):
    def observed(self, before=None):
        window = replay.read_observations(self.path, before or self.before)
        return list(window.diagnostics), list(window.phases)

    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / 'idea.log'
        self.path.write_bytes(b'old unrelated log\n')
        self.before = self.path.stat()

    def test_same_file_reads_only_post_invocation_bytes(self):
        with self.path.open('ab') as stream:
            stream.write(b'kast_semantic_read {"readId":"current"}\n')
        self.assertEqual(([{'readId': 'current'}], []), self.observed())

    def test_exact_old_inode_tail_and_new_head_form_one_bounded_window(self):
        with self.path.open('ab') as stream:
            stream.write(b'kast_semantic_phase {"phase":"RETENTION"}\n')
        self.path.rename(self.root / 'idea.1.log')
        self.path.write_bytes(b'kast_semantic_read {"readId":"current"}\n')
        self.assertEqual(([{'readId': 'current'}], [{'phase': 'RETENTION'}]),
                         self.observed())

    def test_missing_wrong_or_second_rotation_does_not_borrow_older_receipts(self):
        self.path.rename(self.root / 'idea.2.log')
        self.path.write_bytes(b'kast_semantic_read {"readId":"unproven"}\n')
        self.assertEqual(([], []), self.observed())
        previous = self.root / 'idea.1.log'
        previous.write_bytes(b'kast_semantic_read {"readId":"unrelated"}\n')
        self.assertEqual(([], []), self.observed())
        previous.unlink()
        previous.symlink_to(self.root / 'idea.2.log')
        self.assertEqual(([], []), self.observed())

    def test_combined_old_tail_and_new_head_share_the_original_two_mebibyte_cap(self):
        maximum = replay.OBSERVATION_POLICY['maxAppendedBytes']
        with self.path.open('ab') as stream: stream.write(b'x' * maximum)
        self.path.rename(self.root / 'idea.1.log')
        self.path.write_bytes(b'kast_semantic_read {"readId":"over-budget"}\n')
        self.assertEqual(([], []), self.observed())

    def test_truncated_prior_inode_and_malformed_receipts_stay_unavailable(self):
        self.path.write_bytes(b'')
        self.path.rename(self.root / 'idea.1.log')
        self.path.write_bytes(b'kast_semantic_read {"readId":"unproven"}\n')
        self.assertEqual(([], []), self.observed())
        before = self.path.stat()
        with self.path.open('ab') as stream: stream.write(b'kast_semantic_read {malformed}\n')
        self.assertEqual(([], []), self.observed(before))


if __name__ == '__main__': unittest.main()
