"""Contributor-only installed macOS acceptance, never release qualification.

Uses genuine component-qualified native archives. Only release acquisition is
virtualized. The unexercised target and Actions producer IDs are schema fixtures;
their payloads do not exist, so this setup cannot be a sealed release candidate.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import managed_installation as installation
import managed_product_installation as activation
import managed_release as product
from composed_host_probe import HostIdentity, desktop_processes, live_host
from composition_platform import HostPlatform
from private_interpreter import PrivateInterpreter
from product_lifecycle_probe import INVOCATION_FILE, LocalSource, Probe


@dataclass(frozen=True)
class ColdTrial:
    number: int
    host: HostIdentity
    callers: int = 3


@dataclass(frozen=True)
class RepairObservation:
    file: str
    expectedSha256: str
    damagedSha256: str
    restoredSha256: str
    prior: HostIdentity
    replacement: HostIdentity


@dataclass(frozen=True)
class RetirementObservation:
    command: tuple[str, ...]
    exitStatus: int
    damagedBefore: str
    damagedAfter: str
    priorHostAbsent: bool


@dataclass(frozen=True)
class CoordinatorObservation:
    interpreter: str
    modules: tuple[str, ...]
    isolated: bool
    bytecodeWritesDisabled: bool


@dataclass(frozen=True)
class LocalReport:
    sourceRevision: str
    controlSha256: str
    componentReceiptSha256: str
    trials: tuple[ColdTrial, ...]
    repair: RepairObservation
    desktopProcessesBefore: tuple[str, ...]
    desktopProcessesAfter: tuple[str, ...]
    type: Literal["LOCAL_INSTALLED_ACCEPTANCE"] = "LOCAL_INSTALLED_ACCEPTANCE"
    releaseQualification: Literal["NOT_ESTABLISHED"] = "NOT_ESTABLISHED"
    acquisition: Literal["LOCAL_SOURCE_WITH_SCHEMA_FIXTURE"] = "LOCAL_SOURCE_WITH_SCHEMA_FIXTURE"
    repairEntry: Literal["PACKAGED_COORDINATOR_REAL_RETIREMENT"] = (
        "PACKAGED_COORDINATOR_REAL_RETIREMENT"
    )


def fixture_record(
    version: str, revision: str, tree: str, native: product.ProductTarget, receipt: object
) -> product.ProductRecord:
    if (
        native.target != HostPlatform.MACOS_ARM64.value
        or native.runtimeBase is not None
        or not product.mapping(receipt)
        or receipt.get("qualification") != "COMPLETE"
        or receipt.get("sourceRevision") != revision
        or receipt.get("bundleSha256") != native.bundle.sha256
        or not product.mapping(receipt.get("probe"))
        or not product.native_receipt_matches(native, receipt)
    ):
        raise ValueError("exact full native component qualification required")
    other = "linux-x86_64"
    bundle = product.ProductAsset(f"kast-composed-setup-v{version}-{other}.tar.gz", "0" * 64, 1)
    runtime = product.ProductAsset(f"kast-runtime-v{version}-{other}.zip", "0" * 64, 1)
    placeholder = product.ProductTarget(
        other,
        native.inputs,
        bundle,
        runtime,
        product.NativeQualification(
            product.ProductAsset(f"qualification-{other}.json", "0" * 64, 1),
            product.ProductProducer(1, 1, ".github/workflows/composed-linux.yml"),
            bundle.sha256,
            runtime.sha256,
            "0" * 64,
        ),
    )
    record = product.ProductRecord(
        2,
        product.REPOSITORY,
        revision,
        tree,
        version,
        product.ProductProducer(1, 1, ".github/workflows/ci.yml"),
        (placeholder, native),
    )
    if product.decode_product_record(installation.encoded(record)) != record:
        raise ValueError("local schema fixture rejected")
    return record


def asset(path: Path, name: str) -> product.ProductAsset:
    if path.is_symlink() or not path.is_file():
        raise ValueError("regular native input required")
    return product.ProductAsset(name, product.file_digest(path), path.stat().st_size)


def prepare_inputs(
    bundle: Path, runtime: Path, qualification: Path, work: Path
) -> product.ProductRecord:
    with zipfile.ZipFile(runtime) as archive:
        raw = product.document(archive.read("manifest.json"))
        if not product.mapping(raw) or not isinstance(version := raw.get("version"), str):
            raise ValueError("native runtime identity missing")
        from managed_product import runtime_manifest

        metadata = runtime_manifest(
            archive.read("manifest.json"), version, HostPlatform.MACOS_ARM64
        )
        java = hashlib.sha256(
            archive.read("host/Kast.app/Contents/jbr/Contents/Home/release")
        ).hexdigest()
    directory = work / "inputs"
    payload = directory / "payload"
    payload.mkdir(parents=True)
    with tarfile.open(bundle, "r|gz") as archive:
        control_name = f"setup-kast/assets/kast-control-v{version}-macos-aarch64.tar.gz"
        for member in archive:
            if member.name == control_name and member.isfile():
                source = archive.extractfile(member)
                assert source is not None
                with source, (work / "control.tar.gz").open("xb") as output:
                    shutil.copyfileobj(source, output)
                break
        else:
            raise ValueError("native Control archive missing")
    coordinator = work / "coordinator"
    if isinstance(product.extract_control(work / "control.tar.gz", coordinator), product.Failure):
        raise ValueError("native Control rejected")
    identity = product.document((coordinator / "share/kast/build-identity.json").read_bytes())
    if (
        not product.mapping(identity)
        or identity.get("version") != version
        or not product.revision(revision := identity.get("sourceRevision"))
        or not product.revision(tree := identity.get("sourceTree"))
    ):
        raise ValueError("native Control identity rejected")
    receipt = product.document(qualification.read_bytes())
    native = product.ProductTarget(
        HostPlatform.MACOS_ARM64.value,
        product.ProductInputs(
            java,
            "0" * 64,
            product.file_digest(Path(__file__).with_name("composition_recipe.py")),
            metadata.distributionSha256,
        ),
        asset(bundle, f"kast-composed-setup-v{version}-macos-aarch64.tar.gz"),
        asset(runtime, f"kast-runtime-v{version}-macos-aarch64.zip"),
        product.NativeQualification(
            asset(qualification, "qualification-macos-aarch64.json"),
            product.ProductProducer(1, 1, ".github/workflows/composed-linux.yml"),
            product.file_digest(bundle),
            product.file_digest(runtime),
            product.file_digest(coordinator / "share/kast/libexec/kast-management"),
        ),
    )
    record = fixture_record(version, revision, tree, native, receipt)
    for source, target in (
        (bundle, native.bundle),
        (runtime, native.runtime),
        (qualification, native.qualification.receipt),
    ):
        # Read-only fixture inputs; production admission makes its own regular copies.
        os.link(source, payload / target.name)
    _ = (directory / "build-record.json").write_bytes(installation.encoded(record))
    return record


def process_absent(pid: int) -> bool:
    try:
        # Signal zero observes existence without signalling. Unlike spawning ps,
        # this works inside the coordinator's explicit macOS sandbox.
        os.kill(pid, 0)
    except ProcessLookupError:
        return True
    except PermissionError:
        raise ValueError("process observation failed") from None
    return False


def activate_local(args: argparse.Namespace) -> int:
    """Run with shipped coordinator modules first on sys.path, not source replacements."""
    home = Path(os.environ["HOME"]).resolve()
    root = home / ".local/share/kast"
    work = home.parent
    if (work / "LOCAL_ACCEPTANCE_V1").read_bytes() != b"local-only\n":
        raise ValueError("disposable ownership marker absent")
    modules: list[Path] = []
    for module in (installation, activation, product):
        assert module.__file__ is not None
        modules.append(Path(module.__file__).resolve())
    owners = (work / "coordinator/share/kast", root / "installation/share/kast")
    if not any(all(path.is_relative_to(owner) for path in modules) for owner in owners):
        raise ValueError("activation must use shipped coordinator modules")
    _ = (args.evidence / "coordinator.json").write_bytes(
        installation.encoded(
            CoordinatorObservation(
                sys.executable,
                tuple(map(str, modules)),
                bool(sys.flags.isolated),
                sys.dont_write_bytecode,
            )
        )
    )
    if not sys.flags.isolated or not sys.dont_write_bytecode:
        raise ValueError("packaged coordinator requires isolated, read-only imports")
    directory = work / "inputs"
    record = product.decode_product_record((directory / "build-record.json").read_bytes())
    if isinstance(record, product.Failure):
        raise ValueError("local fixture rejected")
    selection = product.ProductSelection(
        "developer-v" + record.version,
        record,
        product.file_digest(directory / "build-record.json"),
        "PinnedBuild",
        None,
        record.sourceRevision,
    )
    observed: list[RetirementObservation] = []

    def retire(
        arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]:
        if args.damaged is None or args.prior_pid is None or observed:
            raise ValueError("unexpected native retirement")
        damaged = Path(args.damaged)
        before = product.file_digest(damaged)
        result = installation.run_child(arguments, environment, timeout)
        observation = RetirementObservation(
            tuple(arguments),
            result.returncode,
            before,
            product.file_digest(damaged),
            process_absent(args.prior_pid),
        )
        observed.append(observation)
        _ = (args.evidence / "retirement.json").write_bytes(installation.encoded(observation))
        _ = (args.evidence / "retirement.stdout").write_bytes(result.stdout)
        _ = (args.evidence / "retirement.stderr").write_bytes(result.stderr)
        return result

    with tempfile.TemporaryDirectory(prefix="admission-", dir=work) as temporary:
        admitted = product.download_composition(
            selection, "262", Path(temporary).resolve(), LocalSource(directory, record)
        )
        if isinstance(admitted, product.Rejected):
            raise ValueError(f"native product admission rejected: {admitted}")
        # Bundle comparison finished during admission. Activation consumes the
        # independent component archives and runtime, not the expanded setup copy.
        shutil.rmtree(Path(temporary) / "bundle")
        interpreter = PrivateInterpreter.admit(
            args.private_python_archive, args.private_dependency, "macos-aarch64"
        )
        outcome = activation.activate(root, home, admitted, interpreter=interpreter, runner=retire)
        print(installation.encoded(outcome).decode(), end="", flush=True)
        if not isinstance(outcome, activation.ProductInstalled):
            return 1
    if args.damaged is not None and (
        len(observed) != 1
        or observed[0].exitStatus != 0
        or observed[0].damagedBefore != observed[0].damagedAfter
        or not observed[0].priorHostAbsent
    ):
        raise ValueError("repair did not preserve damaged bytes through actual Host retirement")
    return 0


def observe_host(probe: Probe, workspace: Path, case: str) -> HostIdentity:
    containers = probe.root.parent / "kast-workspaces/containers"
    selected = tuple(path for path in containers.iterdir() if path.is_dir())
    if len(selected) != 1:
        raise ValueError("expected one private workspace container")
    value = product.document(
        (probe.root / "installation/share/kast/product-selection.json").read_bytes()
    )
    if not product.mapping(value) or not isinstance(runtime := value.get("runtimeRoot"), str):
        raise ValueError("installed runtime reference missing")
    return live_host(
        selected[0],
        workspace,
        HostPlatform.MACOS_ARM64,
        probe.evidence,
        case,
        runtime_home=Path(runtime) / "host/Kast.app/Contents",
    )


def execute(args: argparse.Namespace) -> LocalReport:
    if (platform.system(), platform.machine()) != ("Darwin", "arm64") or not 2 <= args.trials <= 20:
        raise ValueError("local macOS ARM64 acceptance requires 2–20 cold trials")
    if args.sandbox is None or not args.sandbox.is_file():
        raise ValueError("explicit macOS isolation sandbox required")
    _ = PrivateInterpreter.admit(
        args.private_python_archive, args.private_dependency, "macos-aarch64"
    )
    args.evidence.mkdir(parents=True, exist_ok=False)
    work = Path(tempfile.mkdtemp(prefix="kast-local-installed-")).resolve()
    _ = (work / "LOCAL_ACCEPTANCE_V1").write_bytes(b"local-only\n")
    _ = (args.evidence / "disposable-root.txt").write_text(str(work) + "\n")
    record = prepare_inputs(args.bundle, args.runtime, args.qualification, work)
    PrivateInterpreter.admit(
        args.private_python_archive, args.private_dependency, "macos-aarch64"
    ).stage(work / "coordinator/share/kast")
    home = work / "home"
    home.mkdir(mode=0o700)
    root = home / ".local/share/kast"
    desktop_before = desktop_processes(work, HostPlatform.MACOS_ARM64)
    workspace = work / "workspace"
    repository = Path(__file__).resolve().parents[1]
    _ = shutil.copytree(
        repository / "experiments/host-observation/static-callback-fixture", workspace
    )
    _ = shutil.copytree(repository / "gradle/wrapper", workspace / "gradle/wrapper")
    _ = shutil.copy2(repository / "gradlew", workspace / "gradlew")
    env = {
        "PATH": os.defpath,
        "HOME": str(home),
        "KAST_INSTALL_ROOT": str(root),
        "XDG_CONFIG_HOME": str(home / ".config"),
        "XDG_CACHE_HOME": str(home / ".cache"),
        "XDG_DATA_HOME": str(home / ".local/share"),
        "XDG_STATE_HOME": str(home / ".local/state"),
        "TMPDIR": str(work),
        "GRADLE_USER_HOME": str(work / "gradle"),
        "KAST_OUTPUT_TOON": "0",
    }
    sentinels = (
        home / "preserve",
        home / "foreign-idea-profile/preserve",
        work / "gradle/preserve",
        workspace / "preserve",
    )
    for path in sentinels:
        path.parent.mkdir(parents=True, exist_ok=True)
        _ = path.write_bytes(b"foreign sentinel\n")
    probe = Probe(
        root,
        home,
        args.evidence,
        args.private_python_archive,
        args.private_dependency,
        env,
        sandbox=args.sandbox,
    )

    def activate(case: str, damaged: Path | None = None, prior: HostIdentity | None = None) -> None:
        coordinator = work / "coordinator/share/kast"
        if probe.control.exists():
            coordinator = root / "installation/share/kast"
        python = coordinator / "private-python/bin/python3"
        command = [
            "/usr/bin/sandbox-exec",
            "-f",
            str(args.sandbox),
            str(python),
            "-I",
            "-B",
            "-c",
            "import os,runpy,sys; c,d=sys.argv[1:3]; del sys.argv[1:3]; "
            "sys.path[:0]=[c,c+'/defusedxml.whl',os.path.dirname(d)]; "
            "runpy.run_path(d,run_name='__main__')",
            str(coordinator),
            str(Path(__file__).resolve()),
            "--activate",
            "--private-python-archive",
            str(args.private_python_archive),
            "--private-dependency",
            str(args.private_dependency),
            "--evidence",
            str(probe.evidence),
        ]
        if damaged is not None and prior is not None:
            command += ["--damaged", str(damaged), "--prior-pid", str(prior.pid)]
        result = subprocess.run(
            command, cwd=workspace, env=env, capture_output=True, timeout=1800, check=False
        )
        _ = (probe.evidence / f"{case}.stdout").write_bytes(result.stdout)
        _ = (probe.evidence / f"{case}.stderr").write_bytes(result.stderr)
        if result.returncode:
            raise ValueError(f"packaged coordinator failed: {case}; see {probe.evidence}")

    trials: list[ColdTrial] = []
    try:
        for number in range(1, args.trials + 1):
            probe.evidence = args.evidence / f"trial-{number}"
            probe.evidence.mkdir()
            activate("install")
            lifetime = probe.parallel_first_queries(workspace)
            host = observe_host(probe, workspace, "cold-host")
            if host.workspaceLifetime != lifetime or any(
                item.host.lifetime == host.lifetime for item in trials
            ):
                raise ValueError("cold trial did not establish a fresh, shared compiler Host")
            trials.append(ColdTrial(number, host))
            print(
                f"cold trial {number}: 3 complete callers and warm control; pid={host.pid}",
                flush=True,
            )
            if number != args.trials:
                _ = probe.run("uninstall", workspace, ("uninstall",))
                if (
                    root.exists()
                    or (root.parent / "kast-workspaces").exists()
                    or not process_absent(host.pid)
                ):
                    raise ValueError("uninstall did not settle prior cold trial")
        prior = trials[-1].host
        profile_marker = Path(prior.profile) / "local-acceptance-preserve"
        _ = profile_marker.write_bytes(b"private profile sentinel\n")
        invocation = workspace / INVOCATION_FILE
        invocation_digest = product.file_digest(invocation)
        damaged = Path(prior.runtimeHome) / "Resources/build.txt"
        expected = product.file_digest(damaged)
        damaged.chmod(0o600)
        _ = damaged.write_bytes(b"damaged by disposable local acceptance\n")
        damaged.chmod(0o444)
        bad = product.file_digest(damaged)
        if bad == expected or process_absent(prior.pid):
            raise ValueError("repair requires changed bytes under the live prior Host")
        probe.evidence = args.evidence / "repair"
        probe.evidence.mkdir()
        activate("repair", damaged, prior)
        restored = product.file_digest(damaged)
        if (
            restored != expected
            or not process_absent(prior.pid)
            or profile_marker.read_bytes() != b"private profile sentinel\n"
            or product.file_digest(invocation) != invocation_digest
        ):
            raise ValueError("repair did not restore exact bytes after prior Host exit")
        lifetime = probe.query("after-repair", workspace)
        replacement = observe_host(probe, workspace, "replacement-host")
        if lifetime != replacement.workspaceLifetime or replacement.lifetime == prior.lifetime:
            raise ValueError("repair reused the retired compiler Host")
        repair = RepairObservation(str(damaged), expected, bad, restored, prior, replacement)
        _ = probe.run("uninstall", workspace, ("uninstall",))
        if (
            root.exists()
            or (root.parent / "kast-workspaces").exists()
            or not process_absent(replacement.pid)
        ):
            raise ValueError("final uninstall left managed state or live Host")
        if any(path.read_bytes() != b"foreign sentinel\n" for path in sentinels):
            raise ValueError("installed lifecycle changed a protected sentinel")
        desktop_after = desktop_processes(work, HostPlatform.MACOS_ARM64)
        if desktop_after != desktop_before:
            raise ValueError("desktop IDEA process identities changed during acceptance")
        native = record.targets[1]
        report = LocalReport(
            record.sourceRevision,
            native.qualification.controlSha256,
            native.qualification.receipt.sha256,
            tuple(trials),
            repair,
            desktop_before,
            desktop_after,
        )
        _ = (args.evidence / "local-report.json").write_bytes(installation.encoded(report))
        shutil.rmtree(work)
        return report
    finally:
        # Failed retirement retains its tree. Never erase bytes under an unproven Host.
        if probe.control.exists():
            _ = probe.run("failure-stop", workspace, ("stop", "--all", "--json"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--activate", action="store_true", help=argparse.SUPPRESS)
    for name in ("bundle", "runtime", "qualification"):
        _ = parser.add_argument("--" + name, type=Path)
    _ = parser.add_argument("--private-python-archive", type=Path, required=True)
    _ = parser.add_argument("--private-dependency", type=Path, required=True)
    _ = parser.add_argument("--evidence", type=Path, required=True)
    _ = parser.add_argument("--trials", type=int, default=4)
    _ = parser.add_argument("--sandbox", type=Path)
    _ = parser.add_argument("--damaged", type=Path, help=argparse.SUPPRESS)
    _ = parser.add_argument("--prior-pid", type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    for name in (
        "bundle",
        "runtime",
        "qualification",
        "private_python_archive",
        "private_dependency",
        "evidence",
        "sandbox",
        "damaged",
    ):
        value = getattr(args, name)
        if value is not None:
            setattr(args, name, value.resolve())
    try:
        if args.activate:
            return activate_local(args)
        if any(value is None for value in (args.bundle, args.runtime, args.qualification)):
            parser.error("--bundle, --runtime and --qualification are required")
        print(installation.encoded(execute(args)).decode(), end="")
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        print(f"local installed acceptance rejected: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
