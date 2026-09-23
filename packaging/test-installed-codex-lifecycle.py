#!/usr/bin/env python3
"""Private service-control selection in the installed native acceptance boundary."""

from pathlib import Path
import tempfile
import unittest

from installed_codex_lifecycle import AcceptanceFailure, ServiceControl, ServiceControlAction, ServiceControlKind


class ServiceControlTest(unittest.TestCase):
    def test_private_control_precedes_legacy_and_unsafe_presence_rejects(self):
        with tempfile.TemporaryDirectory(prefix="kast-control-") as directory:
            product = Path(directory).resolve()
            kast = product / "bin/kast-complete"
            kast.parent.mkdir()
            kast.write_text("#!/bin/sh\nexit 0\n")
            kast.chmod(0o700)
            legacy = ServiceControl.admit(product, kast)
            self.assertEqual(ServiceControlKind.LEGACY, legacy.kind)
            self.assertEqual([str(kast), "app-server", "disable"], legacy.command(ServiceControlAction.DISABLE))

            private = product / "share/kast/libexec/kast-service"
            private.parent.mkdir(parents=True)
            private.write_text("#!/bin/sh\nexit 0\n")
            private.chmod(0o700)
            selected = ServiceControl.admit(product, kast)
            self.assertEqual(ServiceControlKind.PRIVATE, selected.kind)
            self.assertEqual([str(private), "enable"], selected.command(ServiceControlAction.ENABLE))

            private.chmod(0o600)
            with self.assertRaises(AcceptanceFailure):
                ServiceControl.admit(product, kast)
            private.unlink()
            private.symlink_to(kast)
            with self.assertRaises(AcceptanceFailure):
                ServiceControl.admit(product, kast)


if __name__ == "__main__":
    unittest.main()
