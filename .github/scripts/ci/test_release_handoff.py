#!/usr/bin/env python3
"""The release handoff retains one exact assembled release and qualifies its macOS Control product."""
import json
import subprocess
import tarfile
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import patch

import release_handoff
from release_handoff import Admitted, Extraction, Failure, Identity, Kind, admit, decode, pack, safe_members, unpack

IDENTITY = Identity("a" * 40, "b" * 40, "0.0.7")


def release_bundle(root: Path, control: dict[str, bytes]) -> Admitted:
    source = root / "source"
    source.mkdir()
    archive = source / f"kast-control-v{IDENTITY.version}-macos-aarch64.tar.gz"
    staging = root / "control"
    for name, data in control.items():
        path = staging / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(0o755)
    with tarfile.open(archive, "w:gz") as output:
        for name in control:
            output.add(staging / name, arcname=name)
    pack(source, root / "bundle", IDENTITY, Kind.RELEASE)
    payload = admit(root / "bundle", IDENTITY, Kind.RELEASE)
    if not isinstance(payload, Admitted):
        raise AssertionError("matching release rejected")
    return payload


class ReleaseHandoffTest(unittest.TestCase):
    def test_qualification_exercises_the_exact_assembled_control_product(self):
        executable = bytes.fromhex("cffaedfe0c000001") + b"control executable"
        library = b"PK\x03\x04jar"
        for gradle_exit in (0, 7):
            with self.subTest(gradle_exit=gradle_exit), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                payload = release_bundle(
                    root, {"share/kast/libexec/kast-management": executable, "lib/kast-cli.jar": library}
                )
                seen = []

                def suite(observed_identity, *arguments, code=gradle_exit):
                    self.assertEqual(IDENTITY, observed_identity)
                    self.assertEqual("publicLauncherTest", arguments[1])
                    product = Path(arguments[0].removeprefix("-PkastControlProduct="))
                    self.assertTrue(arguments[0].startswith("-PkastControlProduct=") and product.is_absolute())
                    self.assertEqual(executable, (product / "share/kast/libexec/kast-management").read_bytes())
                    self.assertEqual(library, (product / "lib/kast-cli.jar").read_bytes())
                    seen.append(product)
                    if code:
                        raise subprocess.CalledProcessError(code, ["./gradlew", *arguments])

                with (
                    patch.object(release_handoff, "ROOT", root),
                    patch.object(release_handoff.platform, "system", return_value="Darwin"),
                    patch.object(release_handoff.platform, "machine", return_value="arm64"),
                    patch.object(release_handoff, "gradle", side_effect=suite) as gradle,
                ):
                    if gradle_exit:
                        with self.assertRaises(subprocess.CalledProcessError) as failure:
                            release_handoff.qualify(payload)
                        self.assertEqual(gradle_exit, failure.exception.returncode)
                    else:
                        release_handoff.qualify(payload)
                    gradle.assert_called_once()
                    self.assertEqual(1, len(seen))

    def test_restore_retains_release_bytes_only_on_the_macos_target(self):
        for system, machine in (("Darwin", "arm64"), ("Linux", "x86_64")):
            with self.subTest(system=system), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                payload = release_bundle(root, {"share/kast/libexec/kast-management": b"exact"})
                with (
                    patch.object(release_handoff, "ROOT", root),
                    patch.object(release_handoff.platform, "system", return_value=system),
                    patch.object(release_handoff.platform, "machine", return_value=machine),
                ):
                    release = root / f"build/release/v{IDENTITY.version}"
                    name = f"kast-control-v{IDENTITY.version}-macos-aarch64.tar.gz"
                    if system == "Darwin":
                        self.assertEqual(release, release_handoff.restore_release(payload))
                        self.assertEqual((root / "source" / name).read_bytes(), (release / name).read_bytes())
                    else:
                        with self.assertRaisesRegex(SystemExit, "WRONG_PLATFORM"):
                            release_handoff.restore_release(payload)
                        self.assertFalse(release.exists())

    def test_assembly_targets_macos_and_retains_the_finalized_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            effects = []

            def gradle(identity, *tasks):
                self.assertEqual(IDENTITY, identity)
                self.assertEqual(
                    ("-PkastControlPlatform=macos-aarch64", "assembleRelease", "generateKastModuleKnowledge"), tasks
                )
                release = root / f"build/release/v{IDENTITY.version}"
                release.mkdir(parents=True)
                (release / "asset").write_bytes(b"assembled")
                effects.append("gradle")

            def finalize(command):
                self.assertIn(".github/scripts/release/finalize_assets.py", command)
                effects.append("finalize")

            with (
                patch.object(release_handoff, "ROOT", root),
                patch.object(release_handoff, "gradle", side_effect=gradle),
                patch.object(release_handoff, "run", side_effect=finalize),
            ):
                release_handoff.assemble(IDENTITY, root / "output")
            self.assertEqual(["gradle", "finalize"], effects)
            accepted = admit(root / "output", IDENTITY, Kind.RELEASE)
            self.assertIsInstance(accepted, Admitted)
            assert isinstance(accepted, Admitted)
            self.assertEqual(Extraction.COMPLETE, unpack(accepted, root / "extracted"))
            self.assertEqual(b"assembled", (root / "extracted/asset").read_bytes())

    def test_identity_kind_and_bytes_survive_transfer(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = root / "inputs"
            inputs.mkdir()
            (inputs / "asset").write_bytes(b"release fixture")
            bundle = root / "bundle"
            pack(inputs, bundle, IDENTITY, Kind.RELEASE)
            accepted = admit(bundle, IDENTITY, Kind.RELEASE)
            self.assertIsInstance(accepted, Admitted)
            assert isinstance(accepted, Admitted)
            for changed in (
                replace(IDENTITY, revision="c" * 40),
                replace(IDENTITY, tree="c" * 40),
                replace(IDENTITY, version="0.0.8"),
            ):
                self.assertEqual(Failure.IDENTITY_MISMATCH, admit(bundle, changed, Kind.RELEASE))
            document = asdict(accepted.record)
            for key in tuple(document):
                malformed = dict(document)
                del malformed[key]
                self.assertEqual(Failure.INVALID_RECORD, decode(malformed))
            self.assertEqual(Failure.INVALID_RECORD, decode(document | {"kind": "native"}))
            (bundle / "record.json").write_text(json.dumps(document | {"extra": True}))
            self.assertEqual(Failure.INVALID_RECORD, admit(bundle, IDENTITY, Kind.RELEASE))
            (bundle / "record.json").write_text('{"kind":"release","kind":"release"}')
            self.assertEqual(Failure.INVALID_RECORD, admit(bundle, IDENTITY, Kind.RELEASE))
            pack(inputs, root / "changed", IDENTITY, Kind.RELEASE)
            (root / "changed/payload.tar.gz").write_bytes(b"changed")
            self.assertEqual(Failure.PAYLOAD_MISMATCH, admit(root / "changed", IDENTITY, Kind.RELEASE))

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
