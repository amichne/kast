"""Purge one proven known-too-old (pre-product) Kast installation before activation.

Product activation rejects an installation without a product selection record, because
nothing proves who owns its runtime and workspace state. With explicit consent
(``--purge-legacy``) this owner removes such an installation instead:

1. admit the legacy payload, its receipt, and its launcher, then run that launcher's own
   ``uninstall --force --json`` and require its exact ``REMOVED`` outcome;
2. stop processes that still run removed legacy code: desktop IDEs that keep the deleted
   ``kast-ide-hosted`` plugin mapped, and Hosts that run from a legacy runtime;
3. remove the residue that force removal leaves: legacy runtimes, workspace containers,
   the reset lock, stale socket roots of stopped holders, and desktop plugin copies.

Agent registrations are never edited: the replacement product recreates the launcher
paths they name. Activation re-admits the result with its ordinary prior-installation
rule, so anything this owner leaves behind still rejects.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import signal
import stat
import subprocess
import sys
import time
import zipfile
from collections.abc import Iterable
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, Protocol

from defusedxml import ElementTree as XML

import managed_installation as install
import managed_product_installation as activation
import managed_update_journal as journal

# The last published release whose installation has no product selection record.
LAST_LEGACY = (0, 51, 3)
TARGETS = ("macos-aarch64", "linux-x86_64")
PLUGIN_ID = "io.github.amichne.kast.ide-hosted"
UNINSTALL_TIMEOUT_SECONDS = 300.0
TERM_GRACE_SECONDS = 40.0
KILL_GRACE_SECONDS = 15.0
POLL_SECONDS = 0.5
OBSERVATION_LIMIT_BYTES = 256 * 1024 * 1024
CHILD_EVIDENCE_LIMIT_BYTES = 64 * 1024
SOCKET_NAME = re.compile(
    r"((?:/private)?/tmp/kast-[0-9a-f]{32})/[lh]/[0-9a-f]{32}/(?:host\.sock|owner\.lock)"
)
LEGACY_RUNTIME = re.compile(
    r"(0\.[1-9][0-9]{0,3}\.(?:0|[1-9][0-9]{0,3}))-(" + "|".join(TARGETS) + ")"
)
PRODUCT_RUNTIME = re.compile(r"[0-9a-f]{64}")
CONTAINER = re.compile(r"[0-9a-f]{32}")


class Stage(StrEnum):
    ADMISSION = "ADMISSION"
    FORCE_UNINSTALL = "FORCE_UNINSTALL"
    HOLDERS = "HOLDERS"
    RESIDUE = "RESIDUE"
    VERIFICATION = "VERIFICATION"


class Failure(StrEnum):
    VERSION_NOT_KNOWN_TOO_OLD = "VERSION_NOT_KNOWN_TOO_OLD"
    INSTALLATION_UNPROVEN = "INSTALLATION_UNPROVEN"
    UNINSTALL_REJECTED = "UNINSTALL_REJECTED"
    UNINSTALL_TIMEOUT = "UNINSTALL_TIMEOUT"
    PROCESS_OBSERVATION_REJECTED = "PROCESS_OBSERVATION_REJECTED"
    HOLDER_SURVIVED = "HOLDER_SURVIVED"
    RESIDUE_UNPROVEN = "RESIDUE_UNPROVEN"
    FILESYSTEM_REJECTED = "FILESYSTEM_REJECTED"


@dataclass(frozen=True, order=True)
class LegacyVersion:
    minor: int
    patch: int

    @staticmethod
    def parse(raw: object) -> LegacyVersion | None:
        """Accept exactly the published 0.M.P releases up to LAST_LEGACY.

        Developer 0.0.N builds are excluded: their numbering is not ordered against
        product builds, which also use 0.0.N.
        """
        match = (
            re.fullmatch(r"0\.([1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})", raw)
            if isinstance(raw, str)
            else None
        )
        if match is None:
            return None
        version = LegacyVersion(int(match[1]), int(match[2]))
        return version if (0, version.minor, version.patch) <= LAST_LEGACY else None

    def __str__(self) -> str:
        return f"0.{self.minor}.{self.patch}"


@dataclass(frozen=True)
class LegacyInstallation:
    root: Path
    launcher: Path
    version: LegacyVersion


@dataclass(frozen=True)
class Observed:
    pid: int
    start: str | None
    names: tuple[str, ...]


@dataclass(frozen=True)
class Residue:
    runtimes: tuple[Path, ...]
    containers: tuple[Path, ...]
    files: tuple[Path, ...]
    plugins: tuple[Path, ...]
    sockets: tuple[Path, ...]

    def paths(self) -> tuple[str, ...]:
        groups = (self.runtimes, self.containers, self.files, self.plugins, self.sockets)
        return tuple(sorted(str(path) for group in groups for path in group))


@dataclass(frozen=True)
class NotApplicable:
    type: Literal["LEGACY_PURGE_NOT_APPLICABLE"] = "LEGACY_PURGE_NOT_APPLICABLE"


@dataclass(frozen=True)
class Planned:
    installationRoot: str
    version: str | None
    holders: tuple[int, ...]
    residue: tuple[str, ...]
    type: Literal["LEGACY_PURGE_PLANNED"] = "LEGACY_PURGE_PLANNED"


@dataclass(frozen=True)
class Purged:
    installationRoot: str
    version: str | None
    retired: tuple[int, ...]
    removed: tuple[str, ...]
    type: Literal["LEGACY_PURGED"] = "LEGACY_PURGED"


@dataclass(frozen=True)
class Rejected:
    stage: Stage
    failure: Failure
    type: Literal["LEGACY_PURGE_REJECTED"] = "LEGACY_PURGE_REJECTED"


@dataclass(frozen=True)
class StageEvidence:
    stage: Stage
    outcome: Literal["STARTED", "COMPLETED", "REJECTED"]
    failure: Failure | None = None
    type: Literal["LEGACY_PURGE_STAGE"] = "LEGACY_PURGE_STAGE"


@dataclass(frozen=True)
class RetirementEvidence:
    pid: int
    signal: Literal["SIGTERM", "SIGKILL"] | None
    outcome: Literal["EXITED", "ALREADY_EXITED", "SURVIVED"]
    type: Literal["LEGACY_HOLDER_RETIREMENT"] = "LEGACY_HOLDER_RETIREMENT"


class Unproven(Exception):
    def __init__(self, failure: Failure) -> None:
        super().__init__(failure.value)
        self.failure = failure


class Effects(Protocol):
    def run(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]: ...
    def observe(self) -> tuple[Observed, ...]:
        """Each record's start identity comes from the same observation as its names."""
        ...

    def start(self, pid: int) -> str | None: ...
    def signal(self, pid: int, number: signal.Signals) -> None: ...
    def pause(self, seconds: float) -> None: ...


def emit(value: StageEvidence | RetirementEvidence) -> None:
    print(install.encoded(value).decode().rstrip(), file=sys.stderr)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def launcher_path(home: Path) -> Path:
    return home / ".local/bin/kast"


def admit_installation(root: Path, home: Path) -> LegacyInstallation | None:
    """Return the proven legacy installation, None for a product installation."""
    active = root / "installation"
    if os.path.lexists(active / "share/kast/product-selection.json"):
        return None
    try:
        document = json.loads(install.read_record(active / "installation.json", 67_108_864))
        version = LegacyVersion.parse(document.get("semanticVersion"))
        if version is None:
            raise Unproven(Failure.VERSION_NOT_KNOWN_TOO_OLD)
        # Re-verifies the manifest and every payload byte the legacy installer recorded.
        _ = install.LIFECYCLE.Installation.admit(str(active))
        receipt = json.loads(install.read_record(root / "management.json"))
        launcher = launcher_path(home)
        info = os.lstat(launcher)
        if (
            document.get("schemaVersion") != 3
            or receipt.get("schemaVersion") != 2
            or receipt.get("installationRoot") != str(root)
            or receipt.get("executable") != str(launcher)
            or not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or receipt.get("executableSha256") != sha256(launcher)
        ):
            raise Unproven(Failure.INSTALLATION_UNPROVEN)
    except (OSError, ValueError, AttributeError, install.LIFECYCLE.Rejected):
        raise Unproven(Failure.INSTALLATION_UNPROVEN) from None
    return LegacyInstallation(root, launcher, version)


def known_legacy(root: Path, home: Path) -> LegacyVersion | None:
    """Effect-free detection used only to explain an ordinary activation rejection."""
    try:
        installation = admit_installation(root, home) if os.path.lexists(root) else None
    except Unproven:
        return None
    return installation.version if installation is not None else None


def force_uninstall(installation: LegacyInstallation, home: Path, effects: Effects) -> None:
    try:
        result = effects.run(
            [str(installation.launcher), "uninstall", "--force", "--json"],
            dict(os.environ) | {"HOME": str(home)},
            UNINSTALL_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        raise Unproven(Failure.UNINSTALL_TIMEOUT) from None
    except OSError:
        raise Unproven(Failure.UNINSTALL_REJECTED) from None
    # The legacy owner's stage records are its own evidence; keep them visible, bounded.
    evidence = result.stderr[-CHILD_EVIDENCE_LIMIT_BYTES:].decode(errors="replace")
    if evidence:
        print(evidence.rstrip(), file=sys.stderr)
    lines = result.stdout.decode(errors="replace").strip().splitlines()
    try:
        removed = json.loads(lines[-1]) if lines else None
    except ValueError:
        removed = None
    if (
        result.returncode != 0
        or removed
        != {"type": "REMOVED", "root": str(installation.root), "operation": "UNINSTALL_FORCE"}
        or os.path.lexists(installation.root)
        or os.path.lexists(installation.launcher)
    ):
        raise Unproven(Failure.UNINSTALL_REJECTED)


def owned_directory(path: Path) -> bool:
    info = os.lstat(path)
    return stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()


def plugin_roots(home: Path) -> tuple[Path, ...]:
    """Desktop JetBrains plugin directories; the product never installs into these."""
    if sys.platform == "darwin":
        base, nested = home / "Library/Application Support/JetBrains", "plugins"
    else:
        base, nested = home / ".local/share/JetBrains", ""
    if not os.path.lexists(base) or not owned_directory(base):
        return ()
    return tuple(
        sorted(
            profile / nested / "kast-ide-hosted" if nested else profile / "kast-ide-hosted"
            for profile in base.iterdir()
            if profile.is_dir() and not profile.is_symlink()
        )
    )


def plugin_identity(directory: Path) -> bool:
    try:
        with zipfile.ZipFile(directory / "lib/kast-ide-hosted.jar") as archive:
            info = archive.getinfo("META-INF/plugin.xml")
            if info.file_size > 1_048_576:
                return False
            element = XML.fromstring(archive.read(info)).find("id")
    except (OSError, KeyError, zipfile.BadZipFile, XML.ParseError):
        return False
    return element is not None and element.text == PLUGIN_ID


def prefixes(home: Path, root: Path, residue: Residue) -> tuple[str, ...]:
    """Paths whose holders run legacy code or own legacy workspace state.

    Every desktop plugin location counts, present or not: an IDE keeps a removed
    plugin's jars mapped under their original names.
    """
    containers = (root.parent / "kast-workspaces/containers",) if residue.containers else ()
    return tuple(str(path) for path in (*plugin_roots(home), *residue.runtimes, *containers))


def stripped(name: str) -> str:
    return name.removesuffix(" (deleted)")


def holders(
    observed: Iterable[Observed], legacy: tuple[str, ...]
) -> tuple[tuple[Observed, ...], tuple[Path, ...]]:
    """Pure selection of holder processes and the socket roots they own."""
    selected: list[Observed] = []
    sockets: set[Path] = set()
    for process in observed:
        if process.pid in {0, 1, os.getpid()}:
            continue
        names = tuple(stripped(name) for name in process.names)
        if any(
            name == prefix or name.startswith(prefix + "/") for name in names for prefix in legacy
        ):
            selected.append(process)
            sockets.update(
                Path(match[1]) for name in names if (match := SOCKET_NAME.fullmatch(name))
            )
    return tuple(sorted(selected, key=lambda item: item.pid)), tuple(sorted(sockets))


def retire(holder: Observed, effects: Effects) -> bool:
    """Signal only while the observed (pid, start) identity is still the same process."""
    escalation: tuple[tuple[signal.Signals, Literal["SIGTERM", "SIGKILL"], float], ...] = (
        (signal.SIGTERM, "SIGTERM", TERM_GRACE_SECONDS),
        (signal.SIGKILL, "SIGKILL", KILL_GRACE_SECONDS),
    )
    for number, name, grace in escalation:
        if holder.start is None or effects.start(holder.pid) != holder.start:
            emit(RetirementEvidence(holder.pid, None, "ALREADY_EXITED"))
            return True
        effects.signal(holder.pid, number)
        for _ in range(int(grace / POLL_SECONDS)):
            if effects.start(holder.pid) != holder.start:
                emit(RetirementEvidence(holder.pid, name, "EXITED"))
                return True
            effects.pause(POLL_SECONDS)
    emit(RetirementEvidence(holder.pid, "SIGKILL", "SURVIVED"))
    return False


def socket_root_shape(path: Path) -> bool:
    if not owned_directory(path):
        return False
    for kind in path.iterdir():
        if kind.name not in {"l", "h"} or not owned_directory(kind):
            return False
        for scope in kind.iterdir():
            if not CONTAINER.fullmatch(scope.name) or not owned_directory(scope):
                return False
            for entry in scope.iterdir():
                info = os.lstat(entry)
                if (
                    entry.name not in {"host.sock", "owner.lock"}
                    or info.st_uid != os.getuid()
                    or not (stat.S_ISSOCK(info.st_mode) or stat.S_ISREG(info.st_mode))
                ):
                    return False
    return True


def plan_residue(
    home: Path, root: Path, installation: LegacyInstallation | None, sockets: tuple[Path, ...]
) -> Residue:
    """Classify every residue entry before any deletion; unknown entries reject."""
    store = root.parent / "kast-workspaces"
    runtimes: list[Path] = []
    containers: list[Path] = []
    files: list[Path] = []
    product_runtime = False
    if os.path.lexists(store):
        if not owned_directory(store):
            raise Unproven(Failure.RESIDUE_UNPROVEN)
        for entry in store.iterdir():
            if entry.name == "container.lock":
                info = os.lstat(entry)
                if (
                    not stat.S_ISREG(info.st_mode)
                    or info.st_size != 0
                    or info.st_uid != os.getuid()
                ):
                    raise Unproven(Failure.RESIDUE_UNPROVEN)
                files.append(entry)
            elif entry.name in {"runtimes", "containers"} and owned_directory(entry):
                for child in entry.iterdir():
                    if entry.name == "containers" and CONTAINER.fullmatch(child.name):
                        containers.append(child)
                    elif (
                        entry.name == "runtimes"
                        and (match := LEGACY_RUNTIME.fullmatch(child.name))
                        and LegacyVersion.parse(match[1]) is not None
                    ):
                        runtimes.append(child)
                    elif entry.name == "runtimes" and PRODUCT_RUNTIME.fullmatch(child.name):
                        product_runtime = True
                    else:
                        raise Unproven(Failure.RESIDUE_UNPROVEN)
            else:
                raise Unproven(Failure.RESIDUE_UNPROVEN)
    # Containers carry no version. Attribute them to legacy state only with that proof
    # and without any product runtime that could own them instead.
    if containers and (product_runtime or (installation is None and not runtimes)):
        raise Unproven(Failure.RESIDUE_UNPROVEN)
    digest = hashlib.sha256(str(root).encode()).hexdigest()[:32]
    reset = root.parent / f".kast-reset-{digest}.json"
    if os.path.lexists(reset.with_name(reset.name + ".lock")):
        lock = reset.with_name(reset.name + ".lock")
        info = os.lstat(lock)
        if (
            os.path.lexists(reset)
            or not stat.S_ISREG(info.st_mode)
            or info.st_size != 0
            or info.st_uid != os.getuid()
        ):
            raise Unproven(Failure.RESIDUE_UNPROVEN)
        files.append(lock)
    plugins = [path for path in plugin_roots(home) if os.path.lexists(path)]
    if any(not owned_directory(path) or not plugin_identity(path) for path in plugins):
        raise Unproven(Failure.RESIDUE_UNPROVEN)
    present = [path for path in sockets if os.path.lexists(path)]
    if any(not socket_root_shape(path) for path in present):
        raise Unproven(Failure.RESIDUE_UNPROVEN)
    return Residue(tuple(runtimes), tuple(containers), tuple(files), tuple(plugins), tuple(present))


def delete_tree(path: Path) -> None:
    """Delete an owned tree, including read-only directories legacy runtimes publish."""
    if not owned_directory(path):
        raise Unproven(Failure.RESIDUE_UNPROVEN)
    # fwalk never follows symbolic links; fchmod acts on the walked directory itself.
    for _, _, _, descriptor in os.fwalk(path):
        os.fchmod(descriptor, stat.S_IMODE(os.fstat(descriptor).st_mode) | stat.S_IRWXU)
    install.LIFECYCLE.delete_tree(path)


def stage(name: Stage) -> Stage:
    emit(StageEvidence(name, "STARTED"))
    return name


def purge(
    root: Path, home: Path, dry_run: bool, effects: Effects | None = None
) -> NotApplicable | Planned | Purged | Rejected:
    effects = effects if effects is not None else SystemEffects()
    current = stage(Stage.ADMISSION)
    try:
        installation = None
        if os.path.lexists(root):
            installation = admit_installation(root, home)
            if installation is None:
                return NotApplicable()
        elif os.path.lexists(launcher_path(home)):
            raise Unproven(Failure.INSTALLATION_UNPROVEN)
        # Classify residue before any effect: an unknown entry rejects while the legacy
        # installation is still intact.
        planned = plan_residue(home, root, installation, ())
        if installation is None and not planned.paths():
            return NotApplicable()
        version = str(installation.version) if installation is not None else None
        legacy = prefixes(home, root, planned)
        if dry_run:
            observed, sockets = holders(effects.observe(), legacy)
            planned = plan_residue(home, root, installation, sockets)
            emit(StageEvidence(current, "COMPLETED"))
            return Planned(
                str(root), version, tuple(item.pid for item in observed), planned.paths()
            )
        if installation is not None:
            current = stage(Stage.FORCE_UNINSTALL)
            force_uninstall(installation, home, effects)
        current = stage(Stage.HOLDERS)
        selected, sockets = holders(effects.observe(), legacy)
        for holder in selected:
            if not retire(holder, effects):
                raise Unproven(Failure.HOLDER_SURVIVED)
        # Fresh observation: nothing may still hold legacy code, state, or socket roots.
        if holders(effects.observe(), legacy + tuple(str(path) for path in sockets))[0]:
            raise Unproven(Failure.HOLDER_SURVIVED)
        current = stage(Stage.RESIDUE)
        residue = plan_residue(home, root, installation, sockets)
        for path in (*residue.runtimes, *residue.containers, *residue.plugins, *residue.sockets):
            delete_tree(path)
        for path in residue.files:
            path.unlink()
        current = stage(Stage.VERIFICATION)
        # The ordinary activation rule is the decisive check for what remains.
        if (
            os.path.lexists(launcher_path(home))
            or not isinstance(activation.admit_prior(root), journal.NoPrevious)
            or any(os.path.lexists(path) for path in plugin_roots(home))
        ):
            raise Unproven(Failure.RESIDUE_UNPROVEN)
        emit(StageEvidence(current, "COMPLETED"))
        return Purged(str(root), version, tuple(item.pid for item in selected), residue.paths())
    except Unproven as problem:
        emit(StageEvidence(current, "REJECTED", problem.failure))
        return Rejected(current, problem.failure)
    except ValueError:
        emit(StageEvidence(current, "REJECTED", Failure.RESIDUE_UNPROVEN))
        return Rejected(current, Failure.RESIDUE_UNPROVEN)
    except (OSError, install.LIFECYCLE.Rejected):
        emit(StageEvidence(current, "REJECTED", Failure.FILESYSTEM_REJECTED))
        return Rejected(current, Failure.FILESYSTEM_REJECTED)


class SystemEffects:
    """Process and signal capabilities; observation failures stop the purge."""

    def run(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]:
        return install.run_child(arguments, environment, timeout)

    def observe(self) -> tuple[Observed, ...]:
        return self.observe_darwin() if sys.platform == "darwin" else self.observe_linux()

    @staticmethod
    def observe_darwin() -> tuple[Observed, ...]:
        try:
            result = subprocess.run(
                ["/usr/sbin/lsof", "-n", "-P", "-w", "-F", "pn", "-u", str(os.getuid())],
                stdin=subprocess.DEVNULL,
                capture_output=True,
                timeout=120,
            )
        except (OSError, subprocess.TimeoutExpired):
            raise Unproven(Failure.PROCESS_OBSERVATION_REJECTED) from None
        # lsof exits 1 when some descriptors vanish mid-scan; an empty listing is no proof.
        if (
            result.returncode not in {0, 1}
            or not result.stdout
            or len(result.stdout) > OBSERVATION_LIMIT_BYTES
        ):
            raise Unproven(Failure.PROCESS_OBSERVATION_REJECTED)
        processes: dict[int, list[str]] = {}
        pid: int | None = None
        for line in result.stdout.decode(errors="surrogateescape").splitlines():
            if line.startswith("p") and line[1:].isdigit():
                pid = int(line[1:])
                processes[pid] = []
            elif line.startswith("n") and pid is not None:
                processes[pid].append(line[1:])
        starts = SystemEffects.darwin_starts()
        return tuple(
            Observed(key, starts.get(key), tuple(value)) for key, value in processes.items()
        )

    @staticmethod
    def darwin_starts(pid: int | None = None) -> dict[int, str]:
        selection = ["-p", str(pid)] if pid is not None else ["-ax"]
        try:
            result = subprocess.run(
                ["/bin/ps", *selection, "-o", "pid=,stat=,lstart="],
                stdin=subprocess.DEVNULL,
                capture_output=True,
                timeout=30,
            )
        except (OSError, subprocess.TimeoutExpired):
            raise Unproven(Failure.PROCESS_OBSERVATION_REJECTED) from None
        if result.returncode not in {0, 1}:
            raise Unproven(Failure.PROCESS_OBSERVATION_REJECTED)
        starts: dict[int, str] = {}
        for line in result.stdout.decode().splitlines():
            fields = line.split(maxsplit=2)
            # Zombies have exited: they hold no mappings and only await reaping.
            if len(fields) == 3 and fields[0].isdigit() and not fields[1].startswith("Z"):
                starts[int(fields[0])] = fields[2].strip()
        return starts

    @staticmethod
    def observe_linux() -> tuple[Observed, ...]:
        observed: list[Observed] = []
        for entry in os.scandir("/proc"):
            if not entry.name.isdigit():
                continue
            try:
                if entry.stat(follow_symlinks=False).st_uid != os.getuid():
                    continue
                start = SystemEffects.linux_start(int(entry.name))
                names = [
                    parts[5]
                    for line in Path(entry.path, "maps")
                    .read_text(errors="surrogateescape")
                    .splitlines()
                    if len(parts := line.split(maxsplit=5)) == 6 and parts[5].startswith("/")
                ]
                for descriptor in os.scandir(Path(entry.path, "fd")):
                    target = os.readlink(descriptor.path)
                    if target.startswith("/"):
                        names.append(target)
            except (FileNotFoundError, ProcessLookupError):
                continue  # The process exited during observation.
            except OSError:
                raise Unproven(Failure.PROCESS_OBSERVATION_REJECTED) from None
            observed.append(Observed(int(entry.name), start, tuple(names)))
        return tuple(observed)

    @staticmethod
    def linux_start(pid: int) -> str | None:
        try:
            raw = Path(f"/proc/{pid}/stat").read_text()
        except (FileNotFoundError, ProcessLookupError):
            return None
        fields = raw.rsplit(")", 1)[-1].split()
        # Zombies have exited: they hold no mappings and only await reaping.
        return None if fields[0] == "Z" else fields[19]

    def start(self, pid: int) -> str | None:
        if sys.platform == "darwin":
            return SystemEffects.darwin_starts(pid).get(pid)
        return SystemEffects.linux_start(pid)

    def signal(self, pid: int, number: signal.Signals) -> None:
        try:
            os.kill(pid, number)
        except ProcessLookupError:
            pass  # Exited between the identity check and the signal; the next check sees it.

    def pause(self, seconds: float) -> None:
        time.sleep(seconds)
