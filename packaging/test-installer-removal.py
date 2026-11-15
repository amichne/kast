"""Product removal rejects unproven ownership and preserves retry evidence.

Complete product journals, interrupted erasure and retained offline execution are
covered by ProductActivationTest in test-managed-build-record.py. These boundary
cases exercise the production adapter without shell installers or native children.
"""

import unittest
from unittest.mock import patch

import managed_product_removal as removal
import product_removal_recovery as recovery
from installer_fixture import InstallerFixture, admitted_tools


class InstallerRemovalTest(unittest.TestCase):
    def test_missing_or_malformed_journal_preserves_foreign_state_on_retry(self) -> None:
        with InstallerFixture(admitted_tools()) as fixture:
            root = fixture.root / "home/.local/share/kast"
            payload = root / "installation"
            payload.mkdir(parents=True)
            protected = payload / "foreign"
            _ = protected.write_bytes(b"not owned")
            journal = fixture.root / "uninstall.json"
            before = protected.stat().st_dev, protected.stat().st_ino, protected.read_bytes()
            for raw, failure in (
                (None, removal.Failure.FILESYSTEM),
                (b"invalid JSON", removal.Failure.ADMISSION),
                (b"{}", removal.Failure.ADMISSION),
            ):
                with self.subTest(journal=raw):
                    if raw is not None:
                        _ = journal.write_bytes(raw)
                    with patch.object(
                        removal.install,
                        "run_child",
                        side_effect=AssertionError("no child permitted"),
                    ):
                        result = removal.remove(root, journal)
                        retry = removal.remove(root, journal)
                    expected = removal.Rejected(failure, removal.Stage.ADMISSION)
                    self.assertEqual(expected, result)
                    self.assertEqual(expected, retry)
                    self.assertEqual(
                        before,
                        (protected.stat().st_dev, protected.stat().st_ino, protected.read_bytes()),
                    )
                    self.assertEqual(raw is not None, journal.exists())
                    if raw is not None:
                        self.assertEqual(raw, journal.read_bytes())
            fixture.mark_passed()

    def test_linked_root_cannot_borrow_ownership_or_erase_target(self) -> None:
        with InstallerFixture(admitted_tools()) as fixture:
            foreign = fixture.root / "foreign"
            foreign.mkdir()
            marker = foreign / "preserve"
            _ = marker.write_bytes(b"foreign installation")
            alias = fixture.root / "alias"
            alias.symlink_to(foreign, target_is_directory=True)
            result = removal.remove(alias, fixture.root / "absent-journal")
            self.assertEqual(
                removal.Rejected(removal.Failure.ADMISSION, removal.Stage.ADMISSION), result
            )
            self.assertTrue(alias.is_symlink())
            self.assertEqual(b"foreign installation", marker.read_bytes())
            fixture.mark_passed()

    def test_retained_execution_prefix_resumes_but_changed_bytes_and_modes_reject(self) -> None:
        with InstallerFixture(admitted_tools()) as fixture:
            destination = fixture.root / "retained/interpreter"
            recovery.copy_bytes(destination, b"admitted", 0o700)
            identity = destination.stat().st_dev, destination.stat().st_ino
            recovery.copy_bytes(destination, b"admitted executable", 0o700)
            recovery.copy_bytes(destination, b"admitted executable", 0o700)
            self.assertEqual(identity, (destination.stat().st_dev, destination.stat().st_ino))
            self.assertEqual(b"admitted executable", destination.read_bytes())
            for raw, mode in ((b"unadmitted executable", 0o700), (b"admitted executable", 0o600)):
                with self.subTest(raw=raw, mode=mode), self.assertRaises(ValueError):
                    recovery.copy_bytes(destination, raw, mode)
                self.assertEqual(b"admitted executable", destination.read_bytes())
                self.assertEqual(0o700, destination.stat().st_mode & 0o777)
            fixture.mark_passed()


if __name__ == "__main__":
    _ = unittest.main()
