"""Coordinator effects use private files and explicit raw process observations."""

import contextlib
import hashlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Literal
from unittest.mock import patch

import managed_installation as install
import managed_update_contract as contract
import managed_update_journal as journal


@dataclass(frozen=True)
class ReceiptFixture:
    schemaVersion: int
    installationRoot: str
    executable: str
    executableSha256: str
    channel: str
    registrations: tuple[install.Registration, ...]
    updatePolicy: journal.TrackStable
    installedBuild: journal.BuildIdentity
    guidance: tuple[install.Guidance, ...] = ()


class CoordinatorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kast-coordinator-")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name).resolve()
        self.root = self.home / "managed"
        self.root.mkdir()
        self.workspace = self.home / "workspace"
        self.workspace.mkdir()
        self.control = self.home / "control"
        native = self.control / "share/kast/libexec/kast-management"
        native.parent.mkdir(parents=True)
        _ = native.write_bytes(b"selected native command")
        native.chmod(0o755)
        self.idea = install.SelectedIdea(
            self.home / "IDEA/Contents", self.home / "profile", self.home / "java", "262", "262.1"
        )
        self.adapter = install.NativeComposition(
            self.root,
            self.home,
            self.idea,
            self.control,
            self.workspace,
            install.Limits(0.01, 0.01, 2),
            {"PATH": os.environ["PATH"]},
        )
        self.identity = journal.BuildIdentity("v2.0.0", "2.0.0", "a" * 40, "b" * 40)

    def test_running_legacy_readmission_reaches_restart_required_without_effects(self) -> None:
        process = contract.ProcessIdentity(123, 456, str(self.idea.home / "MacOS/idea"))
        compatibility = install.release.HostedContract(
            "kast.ide-hosted.runtime.v3",
            "sha256:" + "c" * 64,
            "sha256:" + "d" * 64,
            ("workspace.lifecycle",),
        )
        result = LegacyInspectionResultFixture(
            "original-host",
            str(self.idea.home),
            "262.1",
            InspectionCompatibilityFixture(compatibility),
        )
        attached = InspectionFixture(
            (process,),
            result,
            legacyProfile=LegacyProfileFixture(
                str(self.idea.home), str(self.idea.profile), process
            ),
        )
        fresh = replace(attached, legacyProfile=None)
        runner = RawRunner(
            [install.encoded(attached), install.encoded(fresh)],
            ["ATTACH", "REJECT_UNPROVEN"],
        )
        self.adapter.runner = runner
        receipt = ReceiptFixture(
            2,
            str(self.root),
            str(self.home / "bin/kast"),
            "1" * 64,
            "STABLE",
            (),
            journal.TrackStable(),
            journal.BuildIdentity("v1.0.0", "1.0.0", "c" * 40, "d" * 40),
        )
        path = self.root / "management.json"
        before = install.encoded(receipt)
        _ = path.write_bytes(before)
        identity = journal.Identity.observe(path)
        composition = install.release.AdmittedComposition(
            self.identity,
            journal.TrackStable(),
            self.control,
            self.home / "control.tar.gz",
            self.home / "host.zip",
            self.home / "control-record",
            self.home / "host-record",
            "e" * 64,
            "f" * 64,
            compatibility,
            "9" * 64,
        )
        outcome = install.install_composition(self.adapter, composition, False, False)
        self.assertEqual(
            contract.Rejected(contract.Stage.PREPARATION, contract.Failure.RESTART_REQUIRED),
            outcome,
        )
        runner.finished()
        self.assertEqual(before, path.read_bytes())
        self.assertEqual(identity, journal.Identity.observe(path))
        self.assertFalse((self.root / "recovery/replacement").exists())
        self.assertFalse((self.root / "shutdown.json").exists())
        self.assertFalse(self.idea.profile.exists())

    def test_legacy_without_endpoint_readmits_through_explicit_attach(self) -> None:
        process = contract.ProcessIdentity(123, 456, str(self.idea.home / "MacOS/idea"))
        attached = InspectionFixture(
            (process,),
            legacyProfile=LegacyProfileFixture(
                str(self.idea.home), str(self.idea.profile), process
            ),
        )
        prior = contract.parse_inspection(
            install.release.document(install.encoded(attached)),
            str(self.idea.home),
            str(self.idea.profile),
        )
        if not isinstance(prior, contract.Legacy):
            self.fail("the attached profile must admit a legacy snapshot")
        runner = RawRunner([install.encoded(attached)], ["ATTACH"])
        self.adapter.runner = runner
        self.assertIs(prior, self.adapter.readmit(prior))
        runner.finished()
        self.assertEqual(contract.LegacyHostUnavailable.UNAVAILABLE, prior.host)

    def test_legacy_readmission_preserves_a_known_blocked_reason(self) -> None:
        prior = contract.Legacy(
            contract.ProcessIdentity(123, 456, str(self.idea.home / "MacOS/idea")),
            str(self.idea.home),
            str(self.idea.profile),
            None,
            None,
        )
        frame = InspectionFixture((prior.process,), BlockedInspectionFixture())
        runner = RawRunner([install.encoded(frame)], ["ATTACH"])
        self.adapter.runner = runner
        with self.assertRaises(install.StageRejected) as rejected:
            _ = self.adapter.readmit(prior)
        self.assertEqual(
            contract.Rejected(
                contract.Stage.PREPARATION, contract.Failure.HOST_REJECTED, "PROFILE_UNAVAILABLE"
            ),
            rejected.exception.failure,
        )
        runner.finished()

    def record(self):
        slot = self.root / "recovery/replacement"
        slot.mkdir(parents=True)
        selection = install.stored_file(
            slot / "selection.json",
            install.encoded(install.SelectionBinding(self.identity, journal.TrackStable())),
        )
        absent = journal.ManagedFileAbsent(str(self.home / "unused"), "ABSENT")
        host = journal.ManagedHostReplacement(
            str(self.idea.profile / "plugins"),
            None,
            None,
            str(self.idea.profile / "plugins/kast-ide-hosted"),
            str(slot / "host-candidate"),
            journal.Identity(1, 2, os.getuid()),
            "c" * 64,
            str(slot / "host-prior"),
            journal.ManagedHostAbsent("ABSENT"),
            "2.0.0",
            "d" * 64,
        )
        core = journal.ManagedComposition(
            journal.ManagedPhase.RESTORING,
            journal.ManagedStrategy.STOPPED,
            str(self.idea.home),
            str(self.idea.profile),
            selection,
            absent,
            absent,
            absent,
            host,
            absent,
            binding=journal.ManagedBinding(self.identity, journal.TrackStable()),
        )
        return journal.Replacement(
            1,
            journal.ReplacementStage.RECOVERY_REQUIRED,
            str(self.root / "installation"),
            journal.Identity(1, 2, os.getuid()),
            journal.NoPrevious("NONE"),
            journal.ManagedReservation(
                "12345678-1234-1234-1234-123456789abc",
                str(self.control),
                "e" * 64,
                "2.0.0",
                "f" * 64,
            ),
            core,
        )

    def test_foreign_registration_blocks_management_rollback_without_losing_bytes(self):
        record = self.record()
        destination = self.root / "management.json"
        previous = ReceiptFixture(
            2,
            str(self.root),
            str(self.home / "bin/kast"),
            "1" * 64,
            "STABLE",
            (),
            journal.TrackStable(),
            journal.BuildIdentity("v1.0.0", "1.0.0", "c" * 40, "d" * 40),
        )
        _ = destination.write_bytes(install.encoded(previous))
        preimage = install.saved_file(
            destination, self.root / "recovery/replacement/management-preimage"
        )
        core = replace(
            journal.managed_parts(record)[1],
            management=preimage,
            publicExecutable=journal.ManagedFileAbsent(previous.executable, "ABSENT"),
        )
        record = replace(record, composition=core)
        document = asdict(
            replace(
                previous,
                installedBuild=self.identity,
                executableSha256=install.release.file_digest(
                    self.control / "share/kast/libexec/kast-management"
                ),
            )
        )
        document["registrations"] = [
            {"connection": "PI", "destination": "foreign", "payloadSha256": "9" * 64}
        ]
        foreign = json.dumps(document).encode()
        _ = destination.write_bytes(foreign)
        with self.assertRaises(install.StageRejected):
            install.restore_file(preimage, self.adapter, record)
        self.assertEqual(foreign, destination.read_bytes())

    def test_host_action_keeps_nonzero_adapter_reason(self):
        process = contract.ProcessIdentity(123, 456, "/idea")
        loaded = contract.Loaded(
            process,
            contract.RuntimeIdentity(
                str(self.idea.home),
                str(self.idea.profile),
                123,
                456,
                "host",
                contract.RuntimeBuild("2.0.0", "a" * 40, "b" * 40),
                "ENABLED",
            ),
            install.release.HostedContract(
                "kast.ide-hosted.runtime.v3",
                "sha256:" + "a" * 64,
                "sha256:" + "b" * 64,
                ("query.run",),
            ),
        )
        raw = b'{"type":"REJECTED","reason":"HOST_MUTATION_RECOVERY_UNRESOLVED"}'
        result = subprocess.CompletedProcess(["bridge"], 1, raw, b"")

        def runner(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            return result

        self.adapter.runner = runner
        with contextlib.nullcontext():
            with self.assertRaises(install.StageRejected) as rejected:
                _ = self.adapter.host_action(loaded, "transaction", "SETTLE")
        self.assertEqual(
            "HOST_MUTATION_RECOVERY_UNRESOLVED", rejected.exception.failure.adapterCode
        )

    def test_mode_only_command_change_rejects_rollback_without_claiming_restoration(self) -> None:
        record = self.record()
        command = self.home / "bin/kast"
        command.parent.mkdir()
        _ = command.write_bytes((self.control / "share/kast/libexec/kast-management").read_bytes())
        command.chmod(0o755)
        preimage = install.saved_file(command, self.root / "recovery/replacement/command-preimage")
        _, core = journal.managed_parts(record)
        record = replace(record, composition=replace(core, publicExecutable=preimage))
        command.chmod(0o644)
        with self.assertRaises(install.StageRejected):
            install.restore_file(preimage, self.adapter, record)
        self.assertEqual(0o644, stat.S_IMODE(command.stat().st_mode))

    def test_atomic_profile_exchange_restores_a_displaced_foreign_edit(self) -> None:
        options = self.home / "options"
        _ = options.write_bytes(b"-Xmx2g\n")
        options.chmod(0o600)
        preimage = install.saved_file(options, self.home / "options-preimage")
        original = install.exchange_files
        calls = 0
        foreign = b"-Xmx9g\n-Dforeign.setting=true\n"

        def concurrent(first: Path, second: Path) -> None:
            nonlocal calls
            calls += 1
            if calls == 1:
                _ = second.write_bytes(foreign)
            original(first, second)

        with (
            patch.object(install, "exchange_files", concurrent),
            self.assertRaises(install.StageRejected),
        ):
            install.replace_file(
                options,
                contract.profile_options(b"-Xmx2g\n"),
                0o600,
                preimage,
                "transaction-options",
            )
        self.assertEqual(foreign, options.read_bytes())
        self.assertEqual(2, calls)
        self.assertFalse(options.with_name(".kast-managed-transaction-options").exists())

    def test_profile_exchange_interruption_resumes_from_exact_displaced_bytes(self) -> None:
        options = self.home / "options"
        _ = options.write_bytes(b"-Xmx2g\n")
        options.chmod(0o600)
        preimage = install.saved_file(options, self.home / "options-preimage")
        selected = contract.profile_options(b"-Xmx2g\n")
        original = install.exchange_files

        def interrupted(first: Path, second: Path) -> None:
            original(first, second)
            raise OSError("lost after file exchange")

        with patch.object(install, "exchange_files", interrupted), self.assertRaises(OSError):
            install.replace_file(options, selected, 0o600, preimage, "transaction-options")
        displaced = options.with_name(".kast-managed-transaction-options")
        self.assertEqual(b"-Xmx2g\n", displaced.read_bytes())
        install.replace_file(options, selected, 0o600, preimage, "transaction-options")
        self.assertEqual(selected, options.read_bytes())
        self.assertFalse(displaced.exists())

    def test_absent_profile_publication_never_overwrites_a_concurrent_file(self) -> None:
        options = self.home / "options"
        preimage = journal.ManagedFileAbsent(str(options), "ABSENT")
        original = os.link

        def concurrent(source: Path, destination: Path, *, follow_symlinks: bool = True) -> None:
            _ = destination.write_bytes(b"foreign settings")
            original(source, destination, follow_symlinks=follow_symlinks)

        with patch.object(os, "link", concurrent), self.assertRaises(FileExistsError):
            install.replace_file(
                options, contract.profile_options(b""), 0o600, preimage, "transaction-options"
            )
        self.assertEqual(b"foreign settings", options.read_bytes())

    def test_owned_native_child_exits_before_interrupt_reaches_recovery(self) -> None:
        harness = self.home / "interrupt-harness.py"
        pid_file = self.home / "owned-child.pid"
        script = """import os,signal,sys
from pathlib import Path
import managed_installation as managed
def interrupted(number, frame):
    raise KeyboardInterrupt()
signal.signal(signal.SIGTERM, interrupted)
code = ("import os,signal; from pathlib import Path; Path("
    + repr(sys.argv[1])
    + ").write_text(str(os.getpid()));"
    + " os.kill(os.getppid(), signal.SIGTERM); signal.pause()")
try:
    managed.run_child([sys.executable, '-c', code], dict(os.environ), 2)
except KeyboardInterrupt:
    pid = int(Path(sys.argv[1]).read_text())
    try:
        os.waitpid(pid, os.WNOHANG)
    except ChildProcessError:
        print('OWNED_CHILD_REAPED')
        raise SystemExit(0)
    raise SystemExit('native child still unowned after interrupt')
raise SystemExit('interrupt was lost')
"""
        _ = harness.write_text(script)
        environment = dict(
            os.environ, HOME=str(self.home), PYTHONPATH=str(Path(install.__file__).parent)
        )
        child = subprocess.run(
            [sys.executable, str(harness), str(pid_file)],
            env=environment,
            capture_output=True,
            timeout=10,
            check=False,
        )
        self.assertEqual(0, child.returncode, child.stderr.decode())
        self.assertEqual(b"OWNED_CHILD_REAPED\n", child.stdout)
        for number in (130, 143):
            state = install.InterruptState(number)
            self.adapter.interrupt = state
            rejected = install.stage_failure(self.adapter, KeyboardInterrupt())
            self.assertEqual(contract.Failure.CANCELLED, rejected.reason)
            self.assertEqual(number, state.exit_code)

    def test_recovery_only_with_fence_never_reads_release_source(self):
        _ = (self.root / "shutdown.json").write_bytes(
            install.encoded(install.ShutdownRequest(str(self.root / "installation")))
        )
        with (
            patch.dict(os.environ, {"HOME": str(self.home)}),
            patch.object(install.platform, "system", return_value="Darwin"),
            patch.object(install.platform, "machine", return_value="arm64"),
            patch.object(
                install.release,
                "resolve_selection",
                side_effect=AssertionError("unexpected selection"),
            ),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            # The root override is intentionally the normal root in this private HOME.
            managed = self.home / ".local/share/kast"
            managed.mkdir(parents=True)
            _ = (managed / "shutdown.json").write_bytes(
                install.encoded(install.ShutdownRequest(str(managed / "installation")))
            )
            self.assertEqual(1, install.main(["--recover-only", "--json"]))


@dataclass(frozen=True)
class PayloadFileFixture:
    path: str
    sha256: str
    mode: int


@dataclass(frozen=True)
class ManifestFixture:
    installationRoot: str
    stateRoot: str
    configuration: str
    workspaceRegistry: str
    payloadFiles: tuple[PayloadFileFixture, ...]
    controlSha256: str
    payloadIdentity: str
    semanticVersion: str
    schemaVersion: int = 3
    externalAnchors: tuple[object, ...] = ()


@dataclass(frozen=True)
class BuildFixture:
    version: str
    sourceRevision: str
    sourceTree: str


@dataclass(frozen=True)
class MetadataFixture:
    productVersion: str
    requiredHostedContract: install.release.HostedContract
    schemaVersion: int = 2
    execution: str = "existing_ide"


@dataclass(frozen=True)
class LimitFixture:
    key: str
    value: int


@dataclass(frozen=True)
class ConfigurationFixture:
    operationalLimits: tuple[LimitFixture, ...] = (
        LimitFixture("installation.host.settlement_timeout", 20),
        LimitFixture("installation.host.activation_timeout", 20),
        LimitFixture("installation.retirement.child_timeout", 2000),
    )


@dataclass(frozen=True)
class InspectionCompatibilityFixture:
    hostedContract: install.release.HostedContract
    hostedPluginVersion: str = "1.0.0"
    ideBuild: str = "262.1"
    kotlinPluginBuild: str = "262.1-IJ"


@dataclass(frozen=True)
class InspectionResultFixture:
    host: str
    runtimeIdentity: contract.RuntimeIdentity
    compatibility: InspectionCompatibilityFixture
    type: str = "inspected"


@dataclass(frozen=True)
class HostResumedFixture:
    host: str
    transaction: str
    type: str = "host_resumed"


@dataclass(frozen=True)
class LegacyInspectionResultFixture:
    host: str
    home: str
    build: str
    compatibility: InspectionCompatibilityFixture
    runtimeIdentity: None = None
    type: str = "inspected"


@dataclass(frozen=True)
class LegacyProfileFixture:
    ideaHome: str
    profile: str
    process: contract.ProcessIdentity


@dataclass(frozen=True)
class BlockedInspectionFixture:
    reason: str = "PROFILE_UNAVAILABLE"
    type: str = "blocked"


@dataclass(frozen=True)
class InspectionFixture:
    processes: tuple[contract.ProcessIdentity, ...] = ()
    result: (
        InspectionResultFixture
        | HostResumedFixture
        | LegacyInspectionResultFixture
        | BlockedInspectionFixture
        | None
    ) = None
    type: str = "INSPECTED"
    legacyProfile: LegacyProfileFixture | None = None


class RawRunner:
    def __init__(
        self,
        frames: list[bytes],
        modes: list[Literal["ATTACH", "REJECT_UNPROVEN"]] | None = None,
    ) -> None:
        self.frames = list(frames)
        self.modes = list(modes) if modes is not None else None
        self.requests: list[str] = []
        self.violations: list[str] = []

    def __call__(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]:
        if len(arguments) != 3 or arguments[1] != "managed-host":
            raise AssertionError(f"unexpected non-observation child: {arguments}")
        if not self.frames:
            raise AssertionError("unexpected excess child observation")
        request = install.release.document(Path(arguments[2]).read_bytes())
        if not install.release.mapping(request) or request.get("type") != "INSPECT":
            raise AssertionError(f"unexpected Host effect: {request}")
        mode = request.get("legacyProfileInspection")
        if self.modes is not None and (not self.modes or mode != self.modes.pop(0)):
            self.violations.append("unexpected legacy profile inspection mode")
            raise AssertionError(f"unexpected legacy profile inspection mode: {mode}")
        frame = self.frames.pop(0)
        response = install.release.document(frame)
        if (
            mode == "REJECT_UNPROVEN"
            and install.release.mapping(response)
            and response.get("legacyProfile") is not None
        ):
            self.violations.append("REJECT_UNPROVEN supplied a legacy profile proof")
            raise AssertionError("REJECT_UNPROVEN cannot manufacture a legacy profile proof")
        self.requests.append(str(request["type"]))
        return subprocess.CompletedProcess(arguments, 0, frame, b"")

    def finished(self) -> None:
        if self.violations:
            raise AssertionError(self.violations)
        if self.frames or self.modes:
            raise AssertionError("unused child observations")


class ExactControlSource:
    def __init__(self, archive: Path, identity: journal.BuildIdentity) -> None:
        self.archive, self.identity = archive, identity
        self.downloads: list[str] = []

    def read(self, url: str, limit: int) -> bytes | install.release.TransferFailure:
        raise AssertionError(f"unexpected catalog/record lookup: {url}")

    def download(
        self, url: str, destination: Path, limit: int
    ) -> install.release.DownloadedFile | install.release.TransferFailure:
        expected = (
            f"{install.release.RELEASE_URL}/{self.identity.releaseTag}/"
            f"kast-control-v{self.identity.version}-{install.release.PLATFORM}.tar.gz"
        )
        if url != expected:
            raise AssertionError(f"changed recorded artifact: {url}")
        raw = self.archive.read_bytes()
        if len(raw) > limit:
            raise AssertionError("fixture exceeds admitted archive bound")
        _ = destination.write_bytes(raw)
        self.downloads.append(url)
        return install.release.DownloadedFile(
            destination, hashlib.sha256(raw).hexdigest(), len(raw)
        )


class FilesystemCoordinatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="kast-composed-recovery-")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name).resolve()
        self.root = self.home / "managed"
        self.root.mkdir()
        self.installation = self.root / "installation"
        self.slot = self.root / "recovery/replacement"
        self.slot.mkdir(parents=True, mode=0o700)
        self.stage = self.root / ".install-selected"
        self.workspace = self.home / "workspace"
        self.workspace.mkdir()
        self.idea = install.SelectedIdea(
            self.home / "IDEA/Contents", self.home / "profile", self.home / "java", "262", "262.1"
        )
        self.identity = journal.BuildIdentity("v2.0.0", "2.0.0", "a" * 40, "b" * 40)
        self.compatibility = install.release.HostedContract(
            "kast.ide-hosted.runtime.v3",
            "sha256:" + "a" * 64,
            "sha256:" + "b" * 64,
            (
                "workspace.lifecycle",
                "query.run",
                "source.read",
                "diagnostic.check",
                "change.plan",
                "change.apply",
                "change.recover",
            ),
        )
        self.old_archive = self.payload(
            self.installation, journal.BuildIdentity("v1.0.0", "1.0.0", "c" * 40, "d" * 40)
        )
        self.selected_archive = self.payload(self.stage, self.identity)
        prior_identity = journal.Identity.observe(self.installation)
        self.previous = journal.PhysicalPrevious(
            "PHYSICAL",
            str(self.installation),
            prior_identity,
            str(self.slot / "payload"),
            str(self.slot / "recovery"),
        )
        self.recovery_bundle(self.root / "recovery/installation", prior_identity)
        self.plugins = self.idea.profile / "plugins"
        prior_host = self.host_payload("1.0.0")
        _ = install.HOST.install(install.HOST.HostInstallRequest(prior_host, self.plugins))
        host_payload = self.host_payload("2.0.0")
        host_record = install.HOST.prepare_managed(
            install.HOST.HostInstallRequest(host_payload, self.plugins), self.slot
        )
        self.host = journal.admit_host_replacement(
            install.RECOVERY.decode(install.RECOVERY.ManagedHostReplacement, asdict(host_record))
        )
        self.destination = self.home / "bin/kast"
        self.destination.parent.mkdir()
        _ = self.destination.write_bytes(
            (self.installation / "share/kast/libexec/kast-management").read_bytes()
        )
        self.destination.chmod(0o755)
        self.prior_receipt = install.ManagementReceipt(
            2,
            str(self.root),
            str(self.destination),
            install.release.file_digest(self.destination),
            "STABLE",
            (),
            journal.TrackStable(),
            journal.BuildIdentity("v1.0.0", "1.0.0", "c" * 40, "d" * 40),
        )
        _ = (self.root / "management.json").write_bytes(install.encoded(self.prior_receipt))
        (self.root / "management.json").chmod(0o600)
        self.options = self.idea.profile / "idea.vmoptions"
        _ = self.options.write_bytes(b"-Xmx2g\n")
        binding = journal.ManagedBinding(self.identity, journal.TrackStable())
        selected = install.stored_file(
            self.slot / "selection.json",
            install.encoded(install.SelectionBinding(self.identity, journal.TrackStable())),
        )
        management = install.saved_file(
            self.root / "management.json", self.slot / "management-preimage"
        )
        executable = install.saved_file(self.destination, self.slot / "command-preimage")
        options = install.saved_file(self.options, self.slot / "options-preimage")
        self.stopped = install.encoded(InspectionFixture())
        runtime = install.stored_file(self.slot / "runtime-preimage", self.stopped)
        core = journal.ManagedComposition(
            journal.ManagedPhase.HOST_COMMITTED,
            journal.ManagedStrategy.STOPPED,
            str(self.idea.home),
            str(self.idea.profile),
            selected,
            management,
            executable,
            options,
            self.host,
            runtime,
            binding=binding,
            priorRuntimeJson=self.stopped.decode(),
        )
        reservation = journal.ManagedReservation(
            "12345678-1234-1234-1234-123456789abc",
            str(self.stage),
            install.release.file_digest(self.selected_archive),
            "2.0.0",
            install.release.file_digest(self.stage / "installation.json"),
        )
        self.record = journal.Replacement(
            1,
            journal.ReplacementStage.COMMITTED,
            str(self.installation),
            journal.Identity.observe(self.stage),
            self.previous,
            reservation,
            core,
        )
        install.RECOVERY.save(self.slot / "receipt.json", self.record)
        _ = self.installation.rename(self.slot / "payload")
        _ = (self.root / "recovery/installation").rename(self.slot / "recovery")
        _ = self.stage.rename(self.installation)
        self.recovery_bundle(self.root / "recovery/installation", self.record.installationIdentity)
        _ = install.HOST.commit_managed(host_record)
        _ = self.options.write_bytes(contract.profile_options(b"-Xmx2g\n"))
        _ = self.destination.write_bytes(
            (self.installation / "share/kast/libexec/kast-management").read_bytes()
        )
        self.adapter = install.NativeComposition(
            self.root,
            self.home,
            self.idea,
            self.installation,
            self.workspace,
            install.Limits(0.02, 0.02, 2),
            {"PATH": os.environ["PATH"]},
        )
        self.selected_receipt = install.selected_management(self.adapter, core)
        _ = (self.root / "management.json").write_bytes(install.encoded(self.selected_receipt))
        install.ensure_fence(self.adapter)
        self.failure = contract.Rejected(
            contract.Stage.HOST_ACTIVATION, contract.Failure.HOST_ACTIVATION_TIMEOUT
        )

    def payload(self, path: Path, identity: journal.BuildIdentity) -> Path:
        (path / "bin").mkdir(parents=True)
        metadata = path / "share/kast"
        (metadata / "libexec").mkdir(parents=True)
        _ = (path / "bin/kast-complete").write_bytes(("private " + identity.version).encode())
        for name in ("kast-management", "kast-service"):
            command = metadata / "libexec" / name
            _ = command.write_bytes((name + " " + identity.version).encode())
            command.chmod(0o755)
        _ = (metadata / "build-identity.json").write_bytes(
            install.encoded(
                BuildFixture(identity.version, identity.buildRevision, identity.sourceTree)
            )
        )
        _ = (metadata / "ide-host.json").write_bytes(
            install.encoded(MetadataFixture(identity.version, self.compatibility))
        )
        _ = (metadata / "configuration-schema.json").write_bytes(
            install.encoded(ConfigurationFixture())
        )
        _ = (metadata / "managed-install-v1").write_bytes(b"1\n")
        archive = self.home / ("control-" + identity.version + ".tar.gz")
        with tarfile.open(archive, "w:gz") as output:
            for directory in ("bin", "share"):
                output.add(path / directory, arcname=directory)
        digest = install.release.file_digest(archive)
        files = tuple(
            PayloadFileFixture(
                item.relative_to(path).as_posix(),
                "sha256:" + install.release.file_digest(item),
                stat.S_IMODE(item.stat().st_mode),
            )
            for item in sorted(path.rglob("*"))
            if item.is_file()
        )
        _ = (path / "installation.json").write_bytes(
            install.encoded(
                ManifestFixture(
                    str(self.installation),
                    str(self.installation / "state"),
                    str(self.installation / "config/environment"),
                    str(self.installation / "config/workspaces.json"),
                    files,
                    "sha256:" + digest,
                    "sha256:" + digest,
                    identity.version,
                )
            )
        )
        (path / "state/mutation").mkdir(parents=True)
        _ = (path / "state/mutation/receipt").write_text(identity.version + " unresolved")
        return archive

    def host_payload(self, version: str) -> object:
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr(
                "META-INF/plugin.xml",
                "<idea-plugin><id>io.github.amichne.kast.ide-hosted</id>"
                + f"<version>{version}</version>"
                + '<idea-version since-build="262" until-build="262.*"/></idea-plugin>',
            )
        path = self.home / ("host-" + version + ".zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("kast-ide-hosted/lib/host.jar", nested.getvalue())
        return install.HOST.HostedPluginPayload(
            path, install.release.file_digest(path), version, "262.1"
        )

    def recovery_bundle(self, path: Path, identity: journal.Identity) -> None:
        path.mkdir(parents=True)
        install.RECOVERY.save(
            path / "receipt.json",
            install.RECOVERY.Receipt(
                3, str(self.installation), identity, None, None, install.RECOVERY.Status.ACTIVE
            ),
        )

    def observed_adapter(self, count: int) -> RawRunner:
        runner = RawRunner([self.stopped] * count)
        self.adapter.runner = runner
        return runner

    def assert_restored(self) -> None:
        self.assertEqual(self.previous.identity, journal.Identity.observe(self.installation))
        self.assertEqual(
            "1.0.0 unresolved", (self.installation / "state/mutation/receipt").read_text()
        )
        self.assertEqual(
            self.prior_receipt,
            install.parse_management((self.root / "management.json").read_bytes()),
        )
        self.assertEqual(b"-Xmx2g\n", self.options.read_bytes())
        self.assertEqual(
            "1.0.0",
            install.HOST.read_active_receipt(
                self.idea.profile / ".kast-plugin-recovery/host-install.json"
            ).version,
        )
        self.assertFalse(self.slot.exists())
        self.assertFalse((self.root / "shutdown.json").exists())

    def loaded_frame(self, lifetime: str = "selected-host") -> bytes:
        process = contract.ProcessIdentity(123, 456, str(self.idea.home / "MacOS/idea"))
        runtime = contract.RuntimeIdentity(
            str(self.idea.home),
            str(self.idea.profile),
            process.pid,
            process.processStartEpochMillis,
            lifetime,
            contract.RuntimeBuild(
                self.identity.version, self.identity.buildRevision, self.identity.sourceTree
            ),
            "ENABLED",
        )
        return install.encoded(
            InspectionFixture(
                (process,),
                InspectionResultFixture(
                    lifetime, runtime, InspectionCompatibilityFixture(self.compatibility)
                ),
            )
        )

    def host_before_replacement(self) -> object:
        host = install.HOST.decode_managed(asdict(self.host))
        install.HOST.rollback_managed(host)
        self.record = replace(
            self.record,
            composition=replace(
                journal.managed_parts(self.record)[1],
                phase=journal.ManagedPhase.CONTROL_COMMITTED,
            ),
        )
        install.RECOVERY.save(self.slot / "receipt.json", self.record)
        return host

    def test_host_replacement_keeps_the_real_host_inventory_receipt_and_prior_bytes(self) -> None:
        host = self.host_before_replacement()
        runner = RawRunner([self.stopped])
        self.adapter.runner = runner
        install.replace_host(self.adapter, contract.Strategy.STOPPED, host)
        runner.finished()
        receipt = install.HOST.read_active_receipt(
            self.idea.profile / ".kast-plugin-recovery/host-install.json"
        )
        active = install.HOST.admit_active(self.plugins, receipt)
        self.assertEqual("2.0.0", active.version)
        self.assertEqual(active.inventory, install.HOST.inventory(self.plugins / "kast-ide-hosted"))
        self.assertTrue((self.slot / "host-prior").is_dir())
        self.assertTrue((self.slot / "host-receipt").is_file())
        self.assertTrue((self.root / "shutdown.json").is_file())
        self.assertEqual(
            self.record,
            journal.admit_replacement(
                install.RECOVERY.load(self.slot / "receipt.json", install.RECOVERY.Replacement)
            ),
        )

    def assert_concurrent_host_replacement_rejected(self, strategy: contract.Strategy) -> None:
        host = self.host_before_replacement()
        destination = self.plugins / "kast-ide-hosted"
        before = install.HOST.inventory(destination)
        destination_identity = journal.Identity.observe(destination)
        candidate_identity = journal.Identity.observe(Path(self.host.candidate))
        control_identity = journal.Identity.observe(self.installation)
        receipt_path = self.idea.profile / ".kast-plugin-recovery/host-install.json"
        receipt = receipt_path.read_bytes()
        record = (self.slot / "receipt.json").read_bytes()
        runner = RawRunner([self.loaded_frame()])
        self.adapter.runner = runner
        rejection = None
        try:
            install.replace_host(self.adapter, strategy, host)
        except install.StageRejected as problem:
            rejection = problem.failure
        self.assertEqual(
            before,
            install.HOST.inventory(destination),
            "a new Host incarnation reached file replacement after Control committed",
        )
        self.assertEqual(destination_identity, journal.Identity.observe(destination))
        self.assertEqual(candidate_identity, journal.Identity.observe(Path(self.host.candidate)))
        self.assertEqual(control_identity, journal.Identity.observe(self.installation))
        self.assertEqual(receipt, receipt_path.read_bytes())
        self.assertEqual(record, (self.slot / "receipt.json").read_bytes())
        self.assertFalse((self.slot / "host-prior").exists())
        self.assertTrue((self.root / "shutdown.json").is_file())
        self.assertEqual(
            contract.Rejected(contract.Stage.HOST_REPLACEMENT, contract.Failure.CONCURRENT_CHANGE),
            rejection,
        )
        runner.finished()

    def test_stopped_concurrent_start_after_control_commit_preserves_host(self) -> None:
        self.assert_concurrent_host_replacement_rejected(contract.Strategy.STOPPED)

    def test_restart_concurrent_start_after_control_commit_preserves_host(self) -> None:
        self.assert_concurrent_host_replacement_rejected(contract.Strategy.NORMAL_RESTART)

    def test_concurrent_start_during_stopped_strategy_never_changes_host_or_control(self) -> None:
        current_host = install.HOST.inventory(self.plugins / "kast-ide-hosted")
        current_control = journal.Identity.observe(self.installation)
        runner = RawRunner([self.loaded_frame()])
        self.adapter.runner = runner
        original = Path.rename

        def interrupted(path: Path, destination: Path) -> Path:
            result = original(path, destination)
            if path == self.plugins / "kast-ide-hosted":
                raise OSError("fault at forbidden Host effect")
            return result

        with patch.object(Path, "rename", interrupted):
            outcome = install.restore_transaction(self.adapter, self.record, self.failure)
        self.assertIsInstance(outcome, contract.RecoveryRequired)
        runner.finished()
        self.assertTrue(
            (self.plugins / "kast-ide-hosted").is_dir(),
            "concurrent start reached Host file replacement",
        )
        self.assertEqual(current_host, install.HOST.inventory(self.plugins / "kast-ide-hosted"))
        self.assertEqual(current_control, journal.Identity.observe(self.installation))
        self.assertTrue((self.root / "shutdown.json").is_file())
        self.assertTrue((self.slot / "payload").is_dir())

    def test_already_current_proves_real_files_and_live_state_without_host_exchange(self) -> None:
        composition = install.release.AdmittedComposition(
            self.identity,
            journal.TrackStable(),
            self.installation,
            self.selected_archive,
            self.home / "host-2.0.0.zip",
            self.home / "control-record",
            self.home / "host-record",
            install.release.file_digest(self.selected_archive),
            self.host.sha256,
            self.compatibility,
            "e" * 64,
        )
        runner = self.observed_adapter(1)
        before = install.HOST.inventory(self.plugins / "kast-ide-hosted")
        outcome = install.already_current(
            self.adapter, composition, contract.Stopped(self.idea.target), False
        )
        self.assertIsInstance(outcome, contract.InstalledNotRunning)
        self.assertEqual(before, install.HOST.inventory(self.plugins / "kast-ide-hosted"))
        runner.finished()
        loaded = contract.parse_inspection(
            install.release.document(self.loaded_frame()),
            str(self.idea.home),
            str(self.idea.profile),
        )
        self.assertIsInstance(loaded, contract.Loaded)
        if not isinstance(loaded, contract.Loaded):
            raise AssertionError("fixture identity rejected")
        old = replace(
            loaded,
            identity=replace(
                loaded.identity, buildIdentity=contract.RuntimeBuild("1.0.0", "c" * 40, "d" * 40)
            ),
        )
        self.assertIsNone(install.already_current(self.adapter, composition, old, False))

    def test_unchanged_loaded_host_resumes_its_exact_settlement_without_a_new_lifetime(
        self,
    ) -> None:
        loaded = contract.parse_inspection(
            install.release.document(self.loaded_frame()),
            str(self.idea.home),
            str(self.idea.profile),
        )
        if not isinstance(loaded, contract.Loaded):
            raise AssertionError("loaded fixture was rejected")
        calls: list[str] = []
        transaction = "exact-transaction"

        def child(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            if calls or len(arguments) != 3 or arguments[1] != "managed-host":
                raise AssertionError("unexpected or repeated child")
            request = install.release.document(Path(arguments[2]).read_bytes())
            expected = install.HostActionRequest(
                str(self.idea.home),
                str(self.idea.profile),
                loaded.identity.hostLifetime,
                transaction,
                "RESUME",
            )
            self.assertEqual(asdict(expected), request)
            calls.append("RESUME")
            return subprocess.CompletedProcess(
                arguments,
                0,
                install.encoded(
                    InspectionFixture(
                        (loaded.process,),
                        HostResumedFixture(loaded.identity.hostLifetime, transaction),
                    )
                ),
                b"",
            )

        self.adapter.runner = child
        self.adapter.resume_unchanged(loaded, loaded, contract.Strategy.HOT_RELOAD, transaction)
        changed = replace(loaded, identity=replace(loaded.identity, hostLifetime="new-lifetime"))
        self.adapter.resume_unchanged(changed, loaded, contract.Strategy.HOT_RELOAD, transaction)
        self.assertEqual(["RESUME"], calls)
        stale = replace(loaded, identity=replace(loaded.identity, autoReload="DISABLED"))
        with self.assertRaises(install.StageRejected) as rejected:
            self.adapter.resume_unchanged(stale, loaded, contract.Strategy.HOT_RELOAD, transaction)
        self.assertEqual(contract.Failure.CONCURRENT_CHANGE, rejected.exception.failure.reason)
        self.assertEqual(["RESUME"], calls)

    def test_current_fastpath_needs_prepared_physical_profile_options(self) -> None:
        composition = install.release.AdmittedComposition(
            self.identity,
            journal.TrackStable(),
            self.installation,
            self.selected_archive,
            self.home / "host-2.0.0.zip",
            self.home / "control-record",
            self.home / "host-record",
            install.release.file_digest(self.selected_archive),
            self.host.sha256,
            self.compatibility,
            "e" * 64,
        )
        before = install.HOST.inventory(self.plugins / "kast-ide-hosted")
        for options in (None, b"-Xmx2g\n-Didea.auto.reload.plugins=false\n"):
            with self.subTest(options=options):
                self.options.unlink(missing_ok=True)
                if options is not None:
                    _ = self.options.write_bytes(options)
                calls: list[str] = []

                def child(
                    arguments: list[str],
                    environment: dict[str, str],
                    timeout: float,
                    calls: list[str] = calls,
                ) -> subprocess.CompletedProcess[bytes]:
                    # At most one read-only observation is permitted if the old fastpath reaches it.
                    if calls or len(arguments) != 3 or arguments[1] != "managed-host":
                        raise AssertionError("unexpected child observation")
                    request = install.release.document(Path(arguments[2]).read_bytes())
                    if not install.release.mapping(request) or request.get("type") != "INSPECT":
                        raise AssertionError("unexpected Host effect")
                    calls.append("INSPECT")
                    return subprocess.CompletedProcess(arguments, 0, self.stopped, b"")

                self.adapter.runner = child
                result = install.already_current(
                    self.adapter, composition, contract.Stopped(self.idea.target), False
                )
                self.assertIsNone(
                    result, "unprepared VM options cannot produce installation success"
                )
                self.assertEqual([], calls)
                self.assertEqual(before, install.HOST.inventory(self.plugins / "kast-ide-hosted"))

    def test_first_absence_restores_no_control_host_receipts_command_or_profile(self) -> None:
        shutil.rmtree(self.slot / "payload")
        shutil.rmtree(self.slot / "recovery")
        shutil.rmtree(self.slot / "host-prior")
        (self.slot / "host-receipt").unlink()
        retained = self.idea.profile / ".kast-plugin-recovery"
        canonical = retained / "host-install.lock"
        os.link(canonical, self.slot / "host-lock")
        self.options.chmod(0o600)
        _ = self.options.write_bytes(contract.profile_options(b""))
        lock = journal.ManagedHostStagedLock(
            journal.Identity.observe(canonical),
            str(self.slot / "host-lock"),
            journal.Identity.observe(retained),
        )
        host = replace(
            self.host,
            pluginRootIdentity=None,
            retainedRootIdentity=None,
            previous=journal.ManagedHostAbsent("ABSENT"),
            lock=lock,
        )
        _, core = journal.managed_parts(self.record)
        for name in ("management-preimage", "command-preimage", "options-preimage"):
            (self.slot / name).unlink()
        core = replace(
            core,
            host=host,
            management=journal.ManagedFileAbsent(str(self.root / "management.json"), "ABSENT"),
            publicExecutable=journal.ManagedFileAbsent(str(self.destination), "ABSENT"),
            profileOptions=journal.ManagedFileAbsent(str(self.options), "ABSENT"),
            profileDirectories=[
                journal.ManagedProfileCreated(
                    str(self.idea.profile), journal.Identity.observe(self.idea.profile), "CREATED"
                ),
                journal.ManagedProfileCreated(
                    str(retained), journal.Identity.observe(retained), "CREATED"
                ),
            ],
        )
        self.record = replace(self.record, previous=journal.NoPrevious("NONE"), composition=core)
        install.RECOVERY.save(self.slot / "receipt.json", self.record)
        runner = self.observed_adapter(2)
        outcome = install.restore_transaction(self.adapter, self.record, self.failure)
        self.assertIsInstance(outcome, contract.RolledBack)
        runner.finished()
        self.assertFalse(self.installation.exists())
        self.assertFalse(self.destination.exists())
        self.assertFalse((self.root / "management.json").exists())
        self.assertFalse(self.idea.profile.exists())
        self.assertFalse((self.root / "shutdown.json").exists())
        self.assertFalse(self.slot.exists())

    def test_real_control_host_receipt_and_profile_owners_restore_one_composition(self) -> None:
        runner = self.observed_adapter(2)
        outcome = install.restore_transaction(self.adapter, self.record, self.failure)
        self.assertIsInstance(outcome, contract.RolledBack)
        runner.finished()
        self.assert_restored()

    def test_control_move_fault_keeps_fence_and_resumes_same_transaction(self) -> None:
        runner = self.observed_adapter(1)
        original = install.RECOVERY.managed_move

        def interrupted(source: Path, destination: Path, witness: object) -> None:
            original(source, destination, witness)
            raise OSError("interrupted after durable Control move")

        with patch.object(install.RECOVERY, "managed_move", interrupted):
            failed = install.restore_transaction(self.adapter, self.record, self.failure)
        self.assertIsInstance(failed, contract.RecoveryRequired)
        runner.finished()
        self.assertTrue((self.root / "shutdown.json").is_file())
        self.assertTrue((self.slot / "receipt.json").is_file())
        self.assertTrue((self.slot / "payload/state/mutation/receipt").is_file())
        self.assertTrue((self.slot / "rejected-control/state/mutation/receipt").is_file())
        current = journal.admit_replacement(
            install.RECOVERY.load(self.slot / "receipt.json", install.RECOVERY.Replacement)
        )
        runner = self.observed_adapter(2)
        restored = install.restore_transaction(self.adapter, current, self.failure)
        self.assertIsInstance(restored, contract.RolledBack)
        runner.finished()
        self.assert_restored()

    def test_host_restore_fault_keeps_all_component_preimages_for_retry(self) -> None:
        runner = self.observed_adapter(1)
        original = Path.rename

        def interrupted(path: Path, destination: Path) -> Path:
            if path == Path(self.host.backup):
                raise OSError("interrupted prior Host move")
            return original(path, destination)

        with patch.object(Path, "rename", interrupted):
            failed = install.restore_transaction(self.adapter, self.record, self.failure)
        self.assertIsInstance(failed, contract.RecoveryRequired)
        runner.finished()
        self.assertTrue((self.slot / "payload").is_dir())
        self.assertTrue((self.slot / "host-prior").is_dir())
        self.assertTrue((self.slot / "options-preimage").is_file())
        self.assertTrue((self.root / "shutdown.json").is_file())
        current = journal.admit_replacement(
            install.RECOVERY.load(self.slot / "receipt.json", install.RECOVERY.Replacement)
        )
        runner = self.observed_adapter(2)
        self.assertIsInstance(
            install.restore_transaction(self.adapter, current, self.failure), contract.RolledBack
        )
        runner.finished()
        self.assert_restored()

    def test_activation_proven_cleanup_retry_never_rolls_back_sealed_baselines(self) -> None:
        _, core = journal.managed_parts(self.record)
        self.record, core = install.advance(
            self.record, core, journal.ManagedPhase.ACTIVATION_PROVEN
        )
        install.RECOVERY.save(self.slot / "receipt.json", self.record)
        runner = self.observed_adapter(1)
        original = Path.unlink

        def interrupted(path: Path, missing_ok: bool = False) -> None:
            if path == self.slot / "runtime-preimage":
                raise OSError("interrupted final preimage cleanup")
            original(path, missing_ok=missing_ok)

        with (
            patch.object(Path, "unlink", interrupted),
            self.assertRaises(install.RECOVERY.Rejected),
        ):
            _ = install.resume_terminal(self.adapter, self.record)
        runner.finished()
        self.assertFalse((self.slot / "payload").exists())
        self.assertFalse((self.slot / "host-prior").exists())
        self.assertTrue((self.root / "shutdown.json").exists())
        runner = RawRunner([self.stopped])
        outcome = install.recover_recorded(
            self.root,
            self.home,
            False,
            ExactControlSource(self.selected_archive, self.identity),
            runner=runner,
        )
        self.assertIsInstance(outcome, contract.InstalledNotRunning)
        runner.finished()
        self.assertEqual(
            self.record.installationIdentity, journal.Identity.observe(self.installation)
        )
        self.assertFalse(self.slot.exists())
        self.assertFalse((self.root / "shutdown.json").exists())

    def test_restored_cleanup_retry_redownloads_only_exact_recorded_bridge(self) -> None:
        runner = self.observed_adapter(2)
        original = Path.unlink

        def interrupted(path: Path, missing_ok: bool = False) -> None:
            if path == self.slot / "runtime-preimage":
                raise OSError("interrupted restored finalization")
            original(path, missing_ok=missing_ok)

        with patch.object(Path, "unlink", interrupted):
            outcome = install.restore_transaction(self.adapter, self.record, self.failure)
        self.assertIsInstance(outcome, contract.RecoveryRequired)
        runner.finished()
        self.assertFalse((self.slot / "rejected-control").exists())
        source = ExactControlSource(self.selected_archive, self.identity)
        runner = RawRunner([self.stopped])
        outcome = install.recover_recorded(self.root, self.home, False, source, runner=runner)
        self.assertIsInstance(outcome, contract.RolledBack)
        runner.finished()
        self.assertEqual(1, len(source.downloads))
        self.assert_restored()


if __name__ == "__main__":
    _ = unittest.main()
