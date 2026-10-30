"""Only matching, intact archives authorize the next platform stage."""

import json
import subprocess
import tarfile
import tempfile
import unittest
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

import native_handoff
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


@dataclass(frozen=True)
class ResourceFixture:
    glob: str


@dataclass(frozen=True)
class ReachabilityFixture:
    resources: tuple[ResourceFixture, ...]


class NativeHandoffTest(unittest.TestCase):
    def test_inputs_and_native_stage_preserve_both_executable_identities(self):
        identity = Identity("a" * 40, "b" * 40, "0.0.7")
        classes = {
            "kast": "io.github.amichne.kast.distribution.cli.KastManagementMainKt",
            "kast-query": "io.github.amichne.kast.cli.publicshell.KastPublicCliMain",
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            java = root / "java"
            java.mkdir()
            (java / "release").write_text("qualified toolchain")
            for name, module in (("kast", "distribution/cli"), ("kast-query", "cli")):
                archive = root / module / "build/distributions/native-inputs.tar.gz"
                archive.parent.mkdir(parents=True)
                fixture = root / name
                fixture.mkdir()
                (fixture / "image.properties").write_text(f"mainClass={classes[name]}\n")
                (fixture / "native-image.args").write_text("--no-fallback\n")
                (fixture / "native-image-macos.args").write_text("--target=macos-aarch64\n")
                for library in ("alpha", "beta"):
                    metadata = fixture / f"reachability-metadata/fixture/{library}/1.0"
                    metadata.mkdir(parents=True)
                    (metadata / "reachability-metadata.json").write_text(
                        json.dumps(
                            asdict(ReachabilityFixture((ResourceFixture(f"{name}/{library}"),)))
                        )
                    )
                with tarfile.open(archive, "w:gz") as bundle:
                    for path in fixture.iterdir():
                        bundle.add(path, arcname=path.name)
            with (
                patch.object(native_handoff, "ROOT", root),
                patch.dict(native_handoff.os.environ, {"JAVA_HOME": str(java)}),
                patch.object(native_handoff, "gradle") as gradle,
            ):
                native_handoff.stage_inputs(identity, root / "input-bundle")
                gradle.assert_called_once_with(
                    identity,
                    ":distribution:cli:assembleNativeImageInputs",
                    ":cli:assembleNativeImageInputs",
                )
                payload = admit(root / "input-bundle", identity, Kind.INPUTS)
                if not isinstance(payload, Admitted):
                    self.fail("matching inputs rejected")
                compiled = []

                def compile_effect(command, *, cwd):
                    executable = Path(command[command.index("-o") + 1])
                    self.assertEqual(classes[executable.name], command[5])
                    self.assertEqual(executable.name, cwd.name)
                    self.assertTrue((cwd / "native-image.args").is_file())
                    self.assertIn(
                        "-H:ConfigurationFileDirectories=reachability-metadata/fixture/alpha/1.0,"
                        "reachability-metadata/fixture/beta/1.0",
                        command,
                    )
                    for library in ("alpha", "beta"):
                        self.assertEqual(
                            (
                                root
                                / executable.name
                                / f"reachability-metadata/fixture/{library}/1.0/reachability-metadata.json"
                            ).read_bytes(),
                            (
                                cwd
                                / f"reachability-metadata/fixture/{library}/1.0/reachability-metadata.json"
                            ).read_bytes(),
                        )
                    compiled.append(executable.name)
                    executable.write_bytes(
                        bytes.fromhex("cffaedfe0c000001") + executable.name.encode()
                    )
                    (executable.parent / "embedded-resources.json").write_text("{}")
                    (executable.parent / f"{executable.name}-build-report.html").write_text(
                        "report"
                    )

                def inspect_effect(command, *, text):
                    self.assertTrue(text)
                    if command[0] == "xcrun":
                        self.assertEqual(["xcrun", "vtool", "-show-build"], command[:3])
                        return "    minos 15.0\n"
                    name = Path(command[0]).name
                    if command[1:] == [
                        "-XX:MissingRegistrationReportingMode=Exit",
                        "query",
                        "plan",
                        "--name",
                        "OrderService",
                    ]:
                        self.assertEqual("kast-query", name)
                        return "query-plan-probe-output\n"
                    self.assertEqual(["--version"] if name == "kast" else ["--help"], command[1:])
                    return "0.0.7\n" if name == "kast" else "kast query [--help]\n"

                with (
                    patch.object(native_handoff.platform, "system", return_value="Darwin"),
                    patch.object(native_handoff.platform, "machine", return_value="arm64"),
                    patch.object(native_handoff, "run", side_effect=compile_effect) as run,
                    patch.object(
                        native_handoff.subprocess,
                        "check_output",
                        side_effect=inspect_effect,
                    ) as inspect,
                ):
                    native_handoff.native(payload, root / "native-bundle")
                self.assertEqual(["kast", "kast-query"], compiled)
                self.assertEqual(2, run.call_count)
                self.assertEqual(5, inspect.call_count)
                self.assertEqual(
                    "query-plan-probe-output\n",
                    (root / "build/reports/native/kast-query/query-plan.stdout").read_text(),
                )
                output = admit(root / "native-bundle", identity, Kind.NATIVE)
                if not isinstance(output, Admitted):
                    self.fail("matching native outputs rejected")
                self.assertEqual(Extraction.COMPLETE, unpack(output, root / "outputs"))
                self.assertEqual(set(classes), {path.name for path in (root / "outputs").iterdir()})
                for name in classes:
                    self.assertEqual(
                        bytes.fromhex("cffaedfe0c000001") + name.encode(),
                        (root / "outputs" / name).read_bytes(),
                    )

    def test_query_plan_registration_failure_rejects_native_qualification(self):
        identity = Identity("a" * 40, "b" * 40, "0.0.7")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "image.properties").write_text("mainClass=fixture.Query\n")
            metadata = root / "reachability-metadata/fixture"
            metadata.mkdir(parents=True)
            (metadata / "reachability-metadata.json").write_text(
                json.dumps(asdict(ReachabilityFixture((ResourceFixture("fixture"),))))
            )

            def inspect_effect(command, *, text):
                self.assertTrue(text)
                if command == [str(root / "kast-query"), "--help"]:
                    return "kast query [--help]\n"
                self.assertEqual(
                    [
                        str(root / "kast-query"),
                        "-XX:MissingRegistrationReportingMode=Exit",
                        "query",
                        "plan",
                        "--name",
                        "OrderService",
                    ],
                    command,
                )
                raise subprocess.CalledProcessError(172, command)

            with (
                patch.object(native_handoff, "ROOT", root),
                patch.dict(native_handoff.os.environ, {"JAVA_HOME": str(root)}),
                patch.object(native_handoff, "run"),
                patch.object(
                    native_handoff.subprocess, "check_output", side_effect=inspect_effect
                ) as inspect,
                self.assertRaisesRegex(SystemExit, "QUERY_PLAN_FAILED"),
            ):
                native_handoff.compile_native(root, root / "kast-query", identity)
            self.assertEqual(2, inspect.call_count)

    def test_missing_metadata_export_rejects_before_native_compilation(self):
        identity = Identity("a" * 40, "b" * 40, "0.0.7")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "image.properties").write_text("mainClass=fixture.Query\n")
            with (
                patch.object(native_handoff, "run") as compile_effect,
                self.assertRaisesRegex(SystemExit, "INVALID_RECORD"),
            ):
                native_handoff.compile_native(root, root / "kast-query", identity)
            compile_effect.assert_not_called()

    def test_assembly_imports_both_macos_executables(self):
        identity = Identity("a" * 40, "b" * 40, "0.0.7")
        header = bytes.fromhex("cffaedfe0c000001")
        with tempfile.TemporaryDirectory(prefix="native handoff ") as temporary:
            root = Path(temporary)
            binaries = root / "binaries"
            binaries.mkdir()
            for name in ("kast", "kast-query"):
                (binaries / name).write_bytes(header + name.encode())
            bundle = root / "bundle"
            pack(binaries, bundle, identity, Kind.NATIVE, "c" * 64)
            payload = admit(bundle, identity, Kind.NATIVE)
            self.assertIsInstance(payload, Admitted)
            if not isinstance(payload, Admitted):
                self.fail("matching artifact rejected")
            with (
                patch.object(native_handoff, "ROOT", root),
                patch.object(native_handoff, "gradle") as gradle,
                patch.object(native_handoff, "run") as run,
                patch.object(native_handoff, "pack") as seal,
            ):
                native_handoff.assemble(payload, root / "release")
                imported = root / "build/imported-native"
                gradle.assert_called_once_with(
                    identity,
                    f"-PkastNativeExecutable={imported / 'kast'}",
                    f"-PkastQueryNativeExecutable={imported / 'kast-query'}",
                    "-PkastControlPlatform=macos-aarch64",
                    "assembleRelease",
                    "generateKastModuleKnowledge",
                )
                self.assertEqual(1, run.call_count)
                self.assertEqual(1, seal.call_count)
                for name in ("kast", "kast-query"):
                    self.assertEqual((binaries / name).read_bytes(), (imported / name).read_bytes())
                    self.assertEqual(0o755, (imported / name).stat().st_mode & 0o777)

    def test_assembly_rejects_missing_foreign_or_extra_native_payloads_before_gradle(self):
        identity = Identity("a" * 40, "b" * 40, "0.0.7")
        header = bytes.fromhex("cffaedfe0c000001")
        for files in (
            {"kast": header},
            {"kast": header, "kast-query": b"\x7fELF"},
            {"kast": b"\x7fELF", "kast-query": header},
            {"kast": header, "kast-query": header, "extra": header},
        ):
            with self.subTest(files=tuple(files)), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                binaries = root / "binaries"
                binaries.mkdir()
                for name, content in files.items():
                    (binaries / name).write_bytes(content)
                pack(binaries, root / "bundle", identity, Kind.NATIVE, "c" * 64)
                payload = admit(root / "bundle", identity, Kind.NATIVE)
                if not isinstance(payload, Admitted):
                    self.fail("matching archive rejected")
                with (
                    patch.object(native_handoff, "ROOT", root),
                    patch.object(native_handoff, "gradle") as gradle,
                    patch.object(native_handoff, "run"),
                    patch.object(native_handoff, "pack"),
                    self.assertRaisesRegex(SystemExit, "WRONG_PLATFORM"),
                ):
                    native_handoff.assemble(payload, root / "release")
                gradle.assert_not_called()

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
