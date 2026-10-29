"""Only matching, intact archives authorize the next platform stage."""

import json
import tarfile
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path

from native_handoff import (
    Admitted,
    Extraction,
    Failure,
    Identity,
    Kind,
    admit,
    decode,
    pack,
    safe_members,
    unpack,
)


class NativeHandoffTest(unittest.TestCase):
    def test_identity_kind_and_bytes_survive_transfer(self):
        identity = Identity("a" * 40, "b" * 40, "0.0.7")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = root / "inputs"
            inputs.mkdir()
            executable = inputs / "kast"
            executable.write_bytes(b"native fixture")
            executable.chmod(0o755)
            bundle = root / "bundle"
            pack(inputs, bundle, identity, Kind.NATIVE, "c" * 64)
            accepted = admit(bundle, identity, Kind.NATIVE)
            self.assertIsInstance(accepted, Admitted)
            if not isinstance(accepted, Admitted):
                self.fail("matching artifact rejected")
            self.assertEqual(Extraction.COMPLETE, unpack(accepted, root / "extracted"))
            self.assertEqual(executable.read_bytes(), (root / "extracted/kast").read_bytes())
            self.assertEqual(0o755, (root / "extracted/kast").stat().st_mode & 0o777)
            for changed in (
                replace(identity, revision="c" * 40),
                replace(identity, tree="c" * 40),
                replace(identity, version="0.0.8"),
            ):
                self.assertEqual(Failure.IDENTITY_MISMATCH, admit(bundle, changed, Kind.NATIVE))
            self.assertEqual(Failure.IDENTITY_MISMATCH, admit(bundle, identity, Kind.RELEASE))
            (bundle / "payload.tar.gz").write_bytes(b"changed")
            self.assertEqual(Failure.PAYLOAD_MISMATCH, admit(bundle, identity, Kind.NATIVE))
            self.assertEqual("c" * 64, accepted.record.toolchainReleaseSha256)
            document = asdict(accepted.record)
            self.assertEqual(
                Failure.INVALID_RECORD, decode(document | {"toolchainReleaseSha256": "wrong"})
            )
            (bundle / "record.json").write_text('{"kind":"native","kind":"release"}')
            self.assertEqual(Failure.INVALID_RECORD, admit(bundle, identity, Kind.NATIVE))
            for key in tuple(document):
                malformed = dict(document)
                del malformed[key]
                self.assertEqual(Failure.INVALID_RECORD, decode(malformed))
            (bundle / "record.json").write_text(json.dumps(document | {"extra": True}))
            self.assertEqual(Failure.INVALID_RECORD, admit(bundle, identity, Kind.NATIVE))

    def test_archive_paths_and_links_cannot_escape(self):
        valid = tarfile.TarInfo("lib/input.jar")
        self.assertTrue(safe_members([valid]))
        self.assertFalse(safe_members([]))
        self.assertFalse(safe_members([valid, valid]))
        for name in ("/outside", "../outside", "lib/../../outside"):
            self.assertFalse(safe_members([tarfile.TarInfo(name)]))
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.FIFOTYPE):
            entry = tarfile.TarInfo("lib/escape")
            entry.type = kind
            self.assertFalse(safe_members([entry]))


if __name__ == "__main__":
    unittest.main()
