"""Contributor and archive rejection retain foreign state without activation."""

import argparse
import hashlib
import io
import sys
import tarfile
import unittest
from pathlib import Path
from unittest.mock import patch

import local_product_install as local
import managed_release as release
from installer_fixture import InstallerFixture


class LocalInstallationTest(unittest.TestCase):
    def test_foreign_root_rejects_before_build_download_or_activation(self) -> None:
        with InstallerFixture({"python3": Path(sys.executable)}) as fixture:
            root = fixture.root / "foreign"
            protected = root / "unproven-owner"
            root.mkdir()
            _ = protected.write_bytes(b"preserve")
            source = local.SourceIdentity(str(fixture.root / "checkout"), "a" * 40, "b" * 40)
            with (
                patch.object(local.platform, "system", return_value="Darwin"),
                patch.object(local.platform, "machine", return_value="arm64"),
                patch.object(local, "admit_source", return_value=source),
                patch.object(local, "fetch", side_effect=AssertionError("unexpected download")),
                patch.object(local, "run_logged", side_effect=AssertionError("unexpected child")),
            ):
                with self.assertRaises(local.Rejected) as rejected:
                    _ = local.install(argparse.Namespace(source=Path(source.checkout), root=root))
            self.assertEqual(local.Stage.ROOT, rejected.exception.stage)
            self.assertEqual(local.Failure.ROOT_REJECTED, rejected.exception.failure)
            self.assertEqual([protected], list(root.iterdir()))
            self.assertEqual(b"preserve", protected.read_bytes())
            self.assertFalse((fixture.root / "checkout").exists())
            fixture.mark_passed()

    def test_changed_cached_artifact_rejects_without_download_or_activation(self) -> None:
        with InstallerFixture({"python3": Path(sys.executable)}) as fixture:
            expected = hashlib.sha256(b"admitted artifact").hexdigest()
            cached = fixture.root / expected / "control.tar.gz"
            cached.parent.mkdir()
            _ = cached.write_bytes(b"changed artifact")
            with patch.object(
                local.urllib.request, "urlopen", side_effect=AssertionError("unexpected download")
            ):
                with self.assertRaises(local.Rejected) as rejected:
                    _ = local.fetch(
                        fixture.root, "control.tar.gz", "https://invalid.example/control", expected
                    )
            self.assertEqual(local.Stage.INPUTS, rejected.exception.stage)
            self.assertEqual(local.Failure.DIGEST_MISMATCH, rejected.exception.failure)
            self.assertEqual(b"changed artifact", cached.read_bytes())
            self.assertFalse((fixture.root / "product").exists())
            fixture.mark_passed()

    def test_control_link_or_traversal_rejects_before_extraction(self) -> None:
        with InstallerFixture({"python3": Path(sys.executable)}) as fixture:
            protected = fixture.root / "foreign"
            _ = protected.write_bytes(b"preserve")
            archive = fixture.root / "control.tar.gz"
            destination = fixture.root / "control"
            for name, link in (("bin/kast", True), ("../foreign", False)):
                with self.subTest(name=name):
                    with tarfile.open(archive, "w:gz") as output:
                        member = tarfile.TarInfo(name)
                        if link:
                            member.type = tarfile.SYMTYPE
                            member.linkname = str(protected)
                            output.addfile(member)
                        else:
                            member.size = len(b"replace")
                            output.addfile(member, io.BytesIO(b"replace"))
                    self.assertEqual(
                        release.Failure.ARTIFACT_INVALID,
                        release.extract_control(archive, destination),
                    )
                    self.assertFalse(destination.exists())
                    self.assertEqual(b"preserve", protected.read_bytes())
            fixture.mark_passed()

    def test_control_archive_entry_boundary_uses_production_admission(self) -> None:
        with InstallerFixture({"python3": Path(sys.executable)}) as fixture:
            archive = fixture.root / "control.tar.gz"
            for count, expected in (
                (7_609, release.Admission.ADMITTED),
                (16_384, release.Admission.ADMITTED),
                (16_385, release.Failure.ARTIFACT_INVALID),
            ):
                with self.subTest(count=count):
                    destination = fixture.root / f"control-{count}"
                    with tarfile.open(archive, "w:gz") as output:
                        for index in range(count):
                            member = tarfile.TarInfo(f"share/knowledge/{index}")
                            output.addfile(member)
                    self.assertEqual(expected, release.extract_control(archive, destination))
                    if expected == release.Admission.ADMITTED:
                        self.assertEqual(
                            count, len(list((destination / "share/knowledge").iterdir()))
                        )
                    else:
                        self.assertFalse(destination.exists())
            fixture.mark_passed()


if __name__ == "__main__":
    _ = unittest.main()
