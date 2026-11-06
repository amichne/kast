"""Coordinate a copied task and the shared installer inside one offline Tart VM."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import signal
import socket
import stat
import subprocess
import tarfile
import time
import uuid
from dataclasses import asdict, dataclass, field
from enum import StrEnum
from pathlib import Path
from types import FrameType
from typing import BinaryIO, Literal

from composed_bundle import BundleManifest, BundleRejected, read_manifest, sha256
from composition_platform import HostPlatform


class Stage(StrEnum):
    ADMISSION = "ADMISSION"
    SNAPSHOT = "SNAPSHOT"
    CLONE = "CLONE"
    CONFIGURE = "CONFIGURE"
    BOOT = "BOOT"
    TRANSFER = "TRANSFER"
    INSTALL = "INSTALL"
    TASK = "TASK"
    EXPORT = "EXPORT"
    STOP = "STOP"
    DELETE = "DELETE"


class Failure(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    UNSUPPORTED_HOST = "UNSUPPORTED_HOST"
    WRONG_BUNDLE = "WRONG_BUNDLE"
    DISK_PRESSURE = "DISK_PRESSURE"
    TOOL_UNAVAILABLE = "TOOL_UNAVAILABLE"
    EFFECT_FAILED = "EFFECT_FAILED"
    TIMED_OUT = "TIMED_OUT"
    CANCELLED = "CANCELLED"
    VM_EXITED = "VM_EXITED"
    VM_STILL_RUNNING = "VM_STILL_RUNNING"
    UNSUPPORTED_SOURCE = "UNSUPPORTED_SOURCE"


@dataclass(frozen=True, slots=True)
class Rejected:
    stage: Stage
    reason: Failure
    exitCode: int = 1
    type: Literal["rejected"] = field(default="rejected", init=False)


@dataclass(frozen=True, slots=True)
class Completed:
    exitCode: Literal[0] = 0
    type: Literal["completed"] = field(default="completed", init=False)


@dataclass(frozen=True, slots=True)
class Event:
    stage: Stage
    result: Completed | Rejected


@dataclass(frozen=True, slots=True)
class Receipt:
    image: str
    bundleSha256: str
    sourceRevision: str
    snapshotSha256: str | None
    taskName: str
    store: str
    tartSha256: str
    coordinatorSha256: str
    guestSha256: str
    cpus: int
    memoryMiB: int
    result: Completed | Rejected
    cleanup: tuple[Event, ...]
    events: tuple[Event, ...]
    schemaVersion: Literal[1] = field(default=1, init=False)
    network: Literal["DISCONNECTED_DATAGRAM"] = field(default="DISCONNECTED_DATAGRAM", init=False)
    nativeAcceptance: Literal["NOT_ASSERTED"] = field(default="NOT_ASSERTED", init=False)


@dataclass(frozen=True, slots=True)
class Request:
    image: str
    bundle: Path
    bundle_sha256: str
    version: str
    repository: Path
    revision: str
    output: Path
    store: Path
    tart: Path
    guest_python: str
    command: tuple[str, ...]
    cpus: int = 2
    memory_mib: int = 4096
    free_space_gib: int = 40
    boot_seconds: int = 180
    task_seconds: int = 1800


@dataclass(frozen=True, slots=True)
class HostFacts:
    system: str
    machine: str
    free_bytes: int
    cpus: int


@dataclass(frozen=True, slots=True)
class _AdmittedTask:
    request: Request


@dataclass(frozen=True, slots=True)
class _OwnedStore:
    path: Path


@dataclass(frozen=True, slots=True)
class _PreparedTask:
    admitted: _AdmittedTask
    store: _OwnedStore


def admit(request: Request, host: HostFacts, bundle: BundleManifest) -> _AdmittedTask | Rejected:
    if (host.system, host.machine) != ("Darwin", "arm64"):
        return Rejected(Stage.ADMISSION, Failure.UNSUPPORTED_HOST)
    if (
        not re.fullmatch(r"[a-z0-9][a-z0-9.:/-]+@sha256:[a-f0-9]{64}", request.image)
        or not re.fullmatch(r"[a-f0-9]{64}", request.bundle_sha256)
        or not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", request.revision)
        or not request.command
        or any("\x00" in arg for arg in request.command)
        or not request.guest_python.startswith("/")
        or not 1 <= request.cpus <= host.cpus
        or not 4096 <= request.memory_mib <= 32768
        or not 8 <= request.free_space_gib <= 1024
        or not 1 <= request.boot_seconds <= 3600
        or not 1 <= request.task_seconds <= 86400
    ):
        return Rejected(Stage.ADMISSION, Failure.INVALID_INPUT)
    if bundle.platform != HostPlatform.MACOS_ARM64 or bundle.version != request.version:
        return Rejected(Stage.ADMISSION, Failure.WRONG_BUNDLE)
    if host.free_bytes < request.free_space_gib * 1024**3:
        return Rejected(Stage.ADMISSION, Failure.DISK_PRESSURE)
    return _AdmittedTask(request)


def launch_arguments(name: str, descriptor: int) -> tuple[str, ...]:
    # The unconnected peer remains open. No process routes its Ethernet frames.
    return (
        "run",
        "--no-graphics",
        "--no-audio",
        "--no-clipboard",
        "--no-usb-accessories",
        f"--net-fd={descriptor}",
        name,
    )


def environment(home: Path, store: Path) -> dict[str, str]:
    return {
        "PATH": os.defpath,
        "HOME": str(home),
        "TART_HOME": str(store),
        "TART_NO_AUTO_PRUNE": "1",
        "LC_ALL": "C",
        "TMPDIR": str(home),
    }


def call(
    command: tuple[str, ...],
    env: dict[str, str],
    stage: Stage,
    output: BinaryIO,
    *,
    timeout: int = 180,
    source: BinaryIO | None = None,
    errors: BinaryIO | None = None,
) -> Completed | Rejected:
    try:
        result = subprocess.run(
            command,
            env=env,
            stdin=source if source is not None else subprocess.DEVNULL,
            stdout=output,
            stderr=errors if errors is not None else output,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return Rejected(stage, Failure.TIMED_OUT, 124)
    except OSError:
        return Rejected(stage, Failure.TOOL_UNAVAILABLE, 127)
    if result.returncode != 0:
        return Rejected(
            stage,
            Failure.EFFECT_FAILED,
            result.returncode if result.returncode > 0 else 128 - result.returncode,
        )
    return Completed(0)


def prepare_store(store: Path) -> _OwnedStore | Rejected:
    if not store.is_absolute() or store.resolve() != store or len(os.fsencode(store)) > 65:
        return Rejected(Stage.ADMISSION, Failure.INVALID_INPUT)
    marker = store / ".kast-task-vm-store"
    try:
        store.mkdir(mode=0o700)
    except FileExistsError:
        pass
    else:
        _ = marker.write_text("kast-task-vm-store-v1\n")
        marker.chmod(0o600)
    facts = store.stat()
    if (
        not store.is_dir()
        or facts.st_uid != os.getuid()
        or stat.S_IMODE(facts.st_mode) != 0o700
        or marker.is_symlink()
        or not marker.is_file()
        or marker.read_text() != "kast-task-vm-store-v1\n"
    ):
        return Rejected(Stage.ADMISSION, Failure.INVALID_INPUT)
    return _OwnedStore(store)


def prepare_task(admitted: _AdmittedTask) -> _PreparedTask | Rejected:
    request = admitted.request
    if (
        not request.output.is_absolute()
        or request.output.resolve() != request.output
        or request.output.exists()
        or request.output.is_relative_to(request.repository)
        or request.store.is_relative_to(request.repository)
        or request.output.is_relative_to(request.store)
        or request.store.is_relative_to(request.output)
    ):
        return Rejected(Stage.ADMISSION, Failure.INVALID_INPUT)
    store = prepare_store(request.store)
    if isinstance(store, Rejected):
        return store
    request.output.mkdir(mode=0o700)
    return _PreparedTask(admitted, store)


def bundle_manifest(archive: Path, digest: str) -> BundleManifest | Rejected:
    if not archive.is_file() or archive.is_symlink() or sha256(archive) != digest:
        return Rejected(Stage.ADMISSION, Failure.WRONG_BUNDLE)
    with tarfile.open(archive) as stream:
        entries = [item for item in stream if item.name == "setup-kast/manifest.json"]
        if len(entries) != 1 or not entries[0].isfile() or entries[0].size > 16_777_216:
            return Rejected(Stage.ADMISSION, Failure.WRONG_BUNDLE)
        content = stream.extractfile(entries[0])
        if content is None:
            return Rejected(Stage.ADMISSION, Failure.WRONG_BUNDLE)
        with content:
            manifest = read_manifest(content.read())
    if isinstance(manifest, BundleRejected):
        return Rejected(Stage.ADMISSION, Failure.WRONG_BUNDLE)
    return manifest


def payload(request: Request) -> Path | Rejected:
    modes = subprocess.run(
        (
            "/usr/bin/git",
            "-C",
            str(request.repository),
            "ls-tree",
            "-r",
            "--format=%(objectmode)",
            request.revision,
        ),
        env={"PATH": os.defpath, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull},
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        timeout=180,
        check=False,
    )
    if modes.returncode != 0:
        return Rejected(Stage.SNAPSHOT, Failure.EFFECT_FAILED, modes.returncode)
    if b"160000" in modes.stdout.splitlines():
        return Rejected(Stage.SNAPSHOT, Failure.UNSUPPORTED_SOURCE)
    snapshot = request.output / "source.tar"
    with snapshot.open("xb") as output:
        result = call(
            (
                "/usr/bin/git",
                "-C",
                str(request.repository),
                "archive",
                "--format=tar",
                request.revision,
            ),
            {"PATH": os.defpath, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull},
            Stage.SNAPSHOT,
            output,
        )
    if isinstance(result, Rejected):
        return result
    package = request.output / "payload.tar"
    modules = (
        "task_vm_guest.py",
        "composed_bundle.py",
        "composition_platform.py",
        "managed_headless.py",
    )
    with tarfile.open(package, "x") as archive:
        archive.add(snapshot, "source.tar", recursive=False)
        archive.add(request.bundle, "bundle.tar.gz", recursive=False)
        for name in modules:
            archive.add(Path(__file__).parent / name, name, recursive=False)
    return package


BOOTSTRAP = (
    "import pathlib,sys,tarfile; p=pathlib.Path('/private/tmp/kt'); p.mkdir(mode=0o700); "
    "tarfile.open(fileobj=sys.stdin.buffer,mode='r|').extractall(p,filter='data')"
)


def run(prepared: _PreparedTask) -> Receipt:
    request = prepared.admitted.request
    name = "kt-" + uuid.uuid4().hex[:12]
    home = request.output / "host-home"
    home.mkdir(mode=0o700)
    env = environment(home, prepared.store.path)
    executable = str(request.tart)
    events: list[Event] = []
    cleanup: list[Event] = []
    vm: subprocess.Popen[bytes] | None = None
    result: Completed | Rejected = Rejected(Stage.ADMISSION, Failure.EFFECT_FAILED)
    snapshot_digest: str | None = None
    active = Stage.ADMISSION
    tart_digest = sha256(request.tart)
    coordinator_digest = sha256(Path(__file__))
    guest_digest = sha256(Path(__file__).with_name("task_vm_guest.py"))

    def invoke(
        stage: Stage,
        args: tuple[str, ...],
        *,
        source: Path | None = None,
        timeout: int | None = None,
    ) -> Completed | Rejected:
        nonlocal active
        active = stage
        try:
            with (request.output / (stage.value.lower() + ".log")).open("ab") as output:
                if source is None:
                    outcome = call(
                        (executable, *args),
                        env,
                        stage,
                        output,
                        timeout=timeout or request.task_seconds,
                    )
                else:
                    with source.open("rb") as incoming:
                        outcome = call(
                            (executable, *args),
                            env,
                            stage,
                            output,
                            timeout=timeout or request.task_seconds,
                            source=incoming,
                        )
        except OSError:
            outcome = Rejected(stage, Failure.EFFECT_FAILED)
        events.append(Event(stage, outcome))
        return outcome

    def execute() -> Completed | Rejected:
        nonlocal vm, snapshot_digest, active
        active = Stage.SNAPSHOT
        prepared = payload(request)
        if isinstance(prepared, Rejected):
            return prepared
        snapshot_digest = sha256(request.output / "source.tar")
        for stage, args in (
            (Stage.CLONE, ("clone", request.image, name)),
            (
                Stage.CONFIGURE,
                (
                    "set",
                    name,
                    "--cpu",
                    str(request.cpus),
                    "--memory",
                    str(request.memory_mib),
                    "--random-mac",
                    "--random-serial",
                ),
            ),
        ):
            step = invoke(stage, args)
            if isinstance(step, Rejected):
                return step
        active = Stage.BOOT
        sender, peer = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
        with sender, peer, (request.output / "vm.log").open("ab") as output:
            vm = subprocess.Popen(
                (executable, *launch_arguments(name, sender.fileno())),
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=output,
                stderr=output,
                pass_fds=(sender.fileno(),),
            )
            deadline = time.monotonic() + request.boot_seconds
            with (request.output / "boot.log").open("ab") as boot_log:
                while True:
                    if vm.poll() is not None:
                        return Rejected(Stage.BOOT, Failure.VM_EXITED)
                    left = deadline - time.monotonic()
                    if left <= 0:
                        return Rejected(Stage.BOOT, Failure.TIMED_OUT, 124)
                    ready = call(
                        (
                            executable,
                            "exec",
                            name,
                            request.guest_python,
                            "-c",
                            "import platform,sys; sys.exit(0 if "
                            "platform.system() == 'Darwin' and platform.machine() == 'arm64' "
                            "and (3,12) <= sys.version_info[:2] < (3,14) else 78)",
                        ),
                        env,
                        Stage.BOOT,
                        boot_log,
                        timeout=max(1, min(5, int(left))),
                    )
                    if isinstance(ready, Completed):
                        events.append(Event(Stage.BOOT, ready))
                        break
                    if ready.exitCode in (78, 127):
                        return ready
                    time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))
            for stage, args, source in (
                (
                    Stage.TRANSFER,
                    ("exec", "-i", name, request.guest_python, "-c", BOOTSTRAP),
                    prepared,
                ),
                (
                    Stage.INSTALL,
                    (
                        "exec",
                        name,
                        request.guest_python,
                        "/private/tmp/kt/task_vm_guest.py",
                        "install",
                        "--bundle-sha256",
                        request.bundle_sha256,
                        "--version",
                        request.version,
                    ),
                    None,
                ),
            ):
                step = invoke(stage, args, source=source)
                if isinstance(step, Rejected):
                    return step
            task = invoke(
                Stage.TASK,
                (
                    "exec",
                    name,
                    request.guest_python,
                    "/private/tmp/kt/task_vm_guest.py",
                    "task",
                    "--",
                    *request.command,
                ),
            )
            if isinstance(task, Rejected) and task.reason == Failure.TIMED_OUT:
                return task
            active = Stage.EXPORT
            with (
                (request.output / "workspace.tar.gz").open("xb") as archive,
                (request.output / "export.log").open("ab") as errors,
            ):
                exported = call(
                    (
                        executable,
                        "exec",
                        name,
                        "/usr/bin/tar",
                        "-C",
                        "/private/tmp/kt",
                        "-czf",
                        "-",
                        "repository",
                    ),
                    env,
                    Stage.EXPORT,
                    archive,
                    timeout=request.task_seconds,
                    errors=errors,
                )
            events.append(Event(Stage.EXPORT, exported))
            return task if isinstance(task, Rejected) else exported

    # Resource effects stay under this coordinator, including cancellation cleanup.
    try:
        result = execute()
    except KeyboardInterrupt:
        result = Rejected(active, Failure.CANCELLED, 130)
    except subprocess.TimeoutExpired:
        result = Rejected(active, Failure.TIMED_OUT, 124)
    except (OSError, ValueError, tarfile.TarError):
        result = Rejected(active, Failure.EFFECT_FAILED)
    finally:
        if vm is not None:
            stopped = Completed(0)
            try:
                if vm.poll() is None:
                    stopped = invoke(Stage.STOP, ("stop", "--timeout", "15", name), timeout=30)
                if vm.poll() is None:
                    vm.terminate()
                    _ = vm.wait(timeout=20)
            except subprocess.TimeoutExpired:
                try:
                    vm.kill()
                    _ = vm.wait(timeout=20)
                except (OSError, subprocess.TimeoutExpired):
                    stopped = Rejected(Stage.STOP, Failure.EFFECT_FAILED)
            except OSError:
                stopped = Rejected(Stage.STOP, Failure.EFFECT_FAILED)
            cleanup.append(Event(Stage.STOP, stopped))
        if vm is None or vm.poll() is not None:
            if any(e.stage == Stage.CLONE and isinstance(e.result, Completed) for e in events):
                deleted = invoke(Stage.DELETE, ("delete", name), timeout=30)
                cleanup.append(Event(Stage.DELETE, deleted))
        else:
            cleanup.append(Event(Stage.DELETE, Rejected(Stage.DELETE, Failure.VM_STILL_RUNNING)))
    receipt = Receipt(
        request.image,
        request.bundle_sha256,
        request.revision,
        snapshot_digest,
        name,
        str(prepared.store.path),
        tart_digest,
        coordinator_digest,
        guest_digest,
        request.cpus,
        request.memory_mib,
        result,
        tuple(cleanup),
        tuple(events),
    )
    _ = (request.output / "receipt.json").write_text(json.dumps(asdict(receipt), indent=2) + "\n")
    return receipt


def cancel(_signum: int, _frame: FrameType | None) -> None:
    raise KeyboardInterrupt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    for name in ("bundle", "repository", "output"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    for name in ("image", "bundle-sha256", "version"):
        _ = parser.add_argument("--" + name, required=True)
    _ = parser.add_argument(
        "--store", type=Path, default=Path(f"/private/tmp/kast-vm-{os.getuid()}")
    )
    _ = parser.add_argument("--revision", default="HEAD")
    _ = parser.add_argument("--tart", default="tart")
    _ = parser.add_argument("--guest-python", default="/opt/homebrew/bin/python3.13")
    for name, default in (
        ("cpus", 2),
        ("memory-mib", 4096),
        ("free-space-gib", 40),
        ("boot-seconds", 180),
        ("task-seconds", 1800),
    ):
        _ = parser.add_argument("--" + name, type=int, default=default)
    _ = parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    result: Receipt | Rejected
    try:
        executable = shutil.which(str(args.tart))
        if executable is None:
            result = Rejected(Stage.ADMISSION, Failure.TOOL_UNAVAILABLE, 127)
        else:
            revision = subprocess.check_output(
                [
                    "/usr/bin/git",
                    "-C",
                    str(args.repository),
                    "rev-parse",
                    "--verify",
                    "--end-of-options",
                    args.revision + "^{commit}",
                ],
                text=True,
                stderr=subprocess.DEVNULL,
            ).strip()
            command = tuple(args.command)
            request = Request(
                args.image,
                Path(args.bundle).resolve(),
                args.bundle_sha256,
                args.version,
                Path(args.repository).resolve(),
                revision,
                args.output,
                args.store,
                Path(executable).resolve(),
                args.guest_python,
                command[1:] if command[:1] == ("--",) else command,
                args.cpus,
                args.memory_mib,
                args.free_space_gib,
                args.boot_seconds,
                args.task_seconds,
            )
            manifest = bundle_manifest(request.bundle, request.bundle_sha256)
            if isinstance(manifest, Rejected):
                result = manifest
            else:
                facts = HostFacts(
                    platform.system(),
                    platform.machine(),
                    shutil.disk_usage(request.store.parent).free,
                    os.cpu_count() or 1,
                )
                admitted = admit(request, facts, manifest)
                if isinstance(admitted, Rejected):
                    result = admitted
                else:
                    prepared = prepare_task(admitted)
                    if isinstance(prepared, Rejected):
                        result = prepared
                    else:
                        previous = signal.signal(signal.SIGTERM, cancel)
                        try:
                            result = run(prepared)
                        finally:
                            _ = signal.signal(signal.SIGTERM, previous)
    except (OSError, ValueError, tarfile.TarError, subprocess.CalledProcessError):
        result = Rejected(Stage.ADMISSION, Failure.EFFECT_FAILED)
    print(json.dumps(asdict(result), indent=2))
    if isinstance(result, Rejected):
        return result.exitCode
    if any(isinstance(event.result, Rejected) for event in result.cleanup):
        return 1
    return result.result.exitCode


if __name__ == "__main__":
    raise SystemExit(main())
