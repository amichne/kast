"""Process observations are injected; these checks neither launch nor qualify IDEA."""
from pathlib import Path
import unittest

from native_host_selection import (NativeHostFailure, NativeHostProcess, NativeHostLifetime,
    RejectedNativeHost, select_native_host, admit_same_host)


class NativeHostSelectionTest(unittest.TestCase):
    launcher = Path('/Applications/IntelliJ IDEA.app/Contents/MacOS/idea')

    def test_explicit_pid_selects_only_the_observed_exact_executable(self):
        rows = f'34151 {self.launcher}\n84768 {self.launcher}\n998 /another/idea\n'
        self.assertEqual(NativeHostProcess(84768), select_native_host(rows, self.launcher, 84768))
        self.assertEqual(RejectedNativeHost(NativeHostFailure.UNAVAILABLE), select_native_host(rows, self.launcher))
        for pid in (998, 444):
            self.assertEqual(RejectedNativeHost(NativeHostFailure.UNAVAILABLE), select_native_host(rows, self.launcher, pid))
        self.assertEqual(NativeHostProcess(34151), select_native_host(f'34151 {self.launcher}\n', self.launcher))

    def test_malformed_and_duplicate_observations_are_rejected(self):
        for row in ('bad', f'x {self.launcher}', f'0 {self.launcher}', f'-1 {self.launcher}',
                    f'１２３ {self.launcher}', f'123 {self.launcher}\n123 {self.launcher}'):
            with self.subTest(row=row):
                self.assertEqual(RejectedNativeHost(NativeHostFailure.INVALID_OBSERVATION),
                                 select_native_host(row, self.launcher, 123))
        for pid in (0, -1, True, '123', 123.0):
            self.assertEqual(RejectedNativeHost(NativeHostFailure.INVALID_PID),
                             select_native_host(f'123 {self.launcher}', self.launcher, pid))

    def test_repin_preserves_process_lifetime_including_pid_reuse(self):
        original = dict(pid=123, processStart='2026-10-10T07:00:00Z')
        self.assertEqual(NativeHostLifetime(NativeHostProcess(123), original['processStart']),
                         admit_same_host(original, dict(original)))
        for changed in (dict(pid=456, processStart=original['processStart']),
                        dict(pid=123, processStart='2026-10-10T08:00:00Z')):
            self.assertEqual(RejectedNativeHost(NativeHostFailure.UNAVAILABLE), admit_same_host(original, changed))
        for invalid in ({}, dict(pid=True, processStart=original['processStart']),
                        dict(pid=123, processStart=''), dict(pid=123, processStart=None)):
            self.assertEqual(RejectedNativeHost(NativeHostFailure.INVALID_OBSERVATION), admit_same_host(original, invalid))
            self.assertEqual(RejectedNativeHost(NativeHostFailure.INVALID_OBSERVATION), admit_same_host(invalid, original))
