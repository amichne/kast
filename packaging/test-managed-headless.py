from __future__ import annotations

import hashlib
import subprocess
import tempfile
import unittest
import zipfile
from dataclasses import asdict, dataclass, replace
from pathlib import Path

import managed_update_contract as contract
from managed_headless import AssemblyRecord, Failure, Input, Rejected, add_bootstrap, admit, wrapper


def inputs(root: Path) -> Input:
    archive = root / "idea.tar.gz"
    _ = archive.write_bytes(b"distribution fixture")
    bootstrap = root / "bootstrap.zip"
    with zipfile.ZipFile(bootstrap, "w") as output:
        output.writestr("kast-managed-headless/lib/kast-managed-headless.jar", b"bootstrap")
    return Input(
        root / "new",
        archive,
        hashlib.sha256(archive.read_bytes()).hexdigest(),
        bootstrap,
        hashlib.sha256(bootstrap.read_bytes()).hexdigest(),
        root,
        "0.0.0",
        "262.10315.125",
    )


@dataclass(frozen=True, slots=True)
class RuntimeModeFixture:
    ideaHome: str
    profile: str
    pid: int
    processStartEpochMillis: int
    hostLifetime: str
    buildIdentity: contract.RuntimeBuild
    autoReload: str
    mode: str


class ManagedHeadlessTest(unittest.TestCase):
    def test_admission_rejects_existing_state_and_wrong_bytes_before_creation(self) -> None:
        with tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary:
            root = Path(temporary).resolve()
            candidate = inputs(root)
            admitted = admit(candidate)
            assert not isinstance(admitted, Rejected)
            assert admitted.inputs == candidate
            assert admit(replace(candidate, archive_sha256="0" * 64)) == Rejected(
                Failure.DIGEST_MISMATCH
            )
            assert not candidate.root.exists()
            candidate.root.mkdir()
            assert admit(candidate) == Rejected(Failure.EXISTING_TARGET)

    def test_bootstrap_preserves_vendor_bytes_and_record_matches_kotlin_shape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = inputs(root)
            home = root / "idea"
            home.mkdir()
            vendor = home / "vendor.jar"
            _ = vendor.write_bytes(b"vendor")
            assert add_bootstrap(home, candidate.bootstrap) == (
                "333c04dd151a2a6831c039cb9a651df29198be8a04e16ce861d4b6a34a11c954"
            )
            assert vendor.read_bytes() == b"vendor"
            assert add_bootstrap(home, candidate.bootstrap) == Rejected(Failure.EXISTING_TARGET)
            record = asdict(AssemblyRecord(1, str(home), "a" * 64, "b" * 64, "262.1", "262.1-IJ"))
            assert set(record) == {
                "schemaVersion",
                "installation",
                "distributionSha256",
                "bootstrapSha256",
                "platformBuild",
                "kotlinPluginBuild",
            }
            assert record["schemaVersion"] == 1

    def test_wrapper_preserves_literal_arguments_and_rejects_absent_assembly(self) -> None:
        with tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary:
            root = Path(temporary).resolve()
            home = root / "idea $(false); 'literal'"
            state = root / "state with spaces"
            home.mkdir()
            executable = state / "home/.local/bin/kast"
            executable.parent.mkdir(parents=True)
            _ = executable.write_text(
                '#!/bin/sh\nprintf "%s\\n" "$HOME" "$KAST_MANAGED_HEADLESS_STATE"\n'
                'printf "<%s>\\n" "$@"\n'
            )
            executable.chmod(0o700)
            command = root / "kast"
            _ = command.write_text(wrapper(home, state))
            command.chmod(0o700)
            literal = "$(false); 'argument'"
            rejected = subprocess.run(
                [str(command), literal], capture_output=True, text=True, check=False
            )
            assert rejected.returncode == 2
            assert rejected.stdout == ""
            _ = (home / "kast-headless.json").write_text("assembly admission belongs to Control\n")
            result = subprocess.run(
                [str(command), literal], capture_output=True, text=True, check=False
            )
            assert result.returncode == 0
            assert result.stdout.splitlines() == [str(state / "home"), str(state), f"<{literal}>"]

    def test_runtime_mode_survives_the_installer_projection(self) -> None:
        wire = RuntimeModeFixture(
            "/owned/idea",
            "/owned/state/home/profile",
            42,
            100,
            "host",
            contract.RuntimeBuild("0.0.0", "a" * 40, "b" * 40),
            "DISABLED",
            "MANAGED_HEADLESS",
        )
        parsed = contract.parse_runtime(asdict(wire))
        assert not isinstance(parsed, contract.Failure)
        assert getattr(parsed, "mode", None) == "MANAGED_HEADLESS"
        assert (
            contract.parse_runtime(asdict(replace(wire, mode="UNPROVEN")))
            == contract.Failure.HOST_RESPONSE_INVALID
        )

    def test_headless_auto_reload_never_grants_an_installer_strategy(self) -> None:
        wire = RuntimeModeFixture(
            "/owned/idea",
            "/owned/state/home/profile",
            42,
            100,
            "host",
            contract.RuntimeBuild("0.0.0", "a" * 40, "b" * 40),
            "ENABLED",
            "MANAGED_HEADLESS",
        )
        assert contract.parse_runtime(asdict(wire)) == contract.Failure.HOST_RESPONSE_INVALID

    def test_runtime_seal_preserves_bytes_execute_bits_and_external_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            tmp_path = Path(temporary)
            from managed_headless import seal_runtime

            home = tmp_path / "runtime"
            home.mkdir()
            executable = home / "idea"
            _ = executable.write_bytes(b"vendor executable")
            executable.chmod(0o755)
            external = tmp_path / "external"
            _ = external.write_bytes(b"protected")
            external.chmod(0o600)
            (home / "external-link").symlink_to(external)
            seal_runtime(home)
            assert executable.read_bytes() == b"vendor executable"
            assert executable.stat().st_mode & 0o777 == 0o555
            assert external.stat().st_mode & 0o777 == 0o600
            assert home.stat().st_mode & 0o222 == 0
            home.chmod(0o755)


if __name__ == "__main__":
    _ = unittest.main()
