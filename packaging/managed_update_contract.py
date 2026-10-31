"""Pure admission and terminal evidence for one managed installation transaction."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from enum import StrEnum
from typing import Literal

import managed_release as release
import managed_update_journal as journal


class Failure(StrEnum):
    RESTART_REQUIRED = "RESTART_REQUIRED"
    CONCURRENT_CHANGE = "CONCURRENT_CHANGE"
    PROFILE_UNAVAILABLE = "PROFILE_UNAVAILABLE"
    IDEA_UNAVAILABLE = "IDEA_UNAVAILABLE"
    TARGET_AMBIGUOUS = "TARGET_AMBIGUOUS"
    OWNERSHIP_UNPROVEN = "OWNERSHIP_UNPROVEN"
    HOST_RESPONSE_INVALID = "HOST_RESPONSE_INVALID"
    HOST_REJECTED = "HOST_REJECTED"
    HOST_SETTLEMENT_TIMEOUT = "HOST_SETTLEMENT_TIMEOUT"
    HOST_ACTIVATION_TIMEOUT = "HOST_ACTIVATION_TIMEOUT"
    FILESYSTEM_REJECTED = "FILESYSTEM_REJECTED"
    CONTROL_REJECTED = "CONTROL_REJECTED"
    HOST_REPLACEMENT_REJECTED = "HOST_REPLACEMENT_REJECTED"
    RECOVERY_UNPROVEN = "RECOVERY_UNPROVEN"
    CHILD_REJECTED = "CHILD_REJECTED"
    CHILD_TIMEOUT = "CHILD_TIMEOUT"
    CANCELLED = "CANCELLED"
    PLATFORM_UNAVAILABLE = "PLATFORM_UNAVAILABLE"


class Stage(StrEnum):
    PREPARATION = "PREPARATION"
    RE_ADMISSION = "RE_ADMISSION"
    JOURNAL = "JOURNAL"
    FENCE = "FENCE"
    REQUEST_RETIREMENT = "REQUEST_RETIREMENT"
    HOST_SETTLEMENT = "HOST_SETTLEMENT"
    PROFILE = "PROFILE"
    CONTROL_REPLACEMENT = "CONTROL_REPLACEMENT"
    HOST_REPLACEMENT = "HOST_REPLACEMENT"
    HOST_ACTIVATION = "HOST_ACTIVATION"
    RECEIPTS = "RECEIPTS"
    FINALIZATION = "FINALIZATION"
    RECOVERY = "RECOVERY"


class Strategy(StrEnum):
    STOPPED = "STOPPED"
    HOT_RELOAD = "HOT_RELOAD"
    NORMAL_RESTART = "NORMAL_RESTART"


@dataclass(frozen=True, slots=True)
class ProcessIdentity:
    pid: int
    processStartEpochMillis: int
    executable: str

    def __post_init__(self) -> None:
        if (
            type(self.pid) is not int
            or self.pid <= 0
            or type(self.processStartEpochMillis) is not int
            or self.processStartEpochMillis <= 0
            or not self.executable.startswith("/")
        ):
            raise ValueError("a process needs its exact positive incarnation and executable")


@dataclass(frozen=True, slots=True)
class RuntimeBuild:
    version: str
    sourceRevision: str
    sourceTree: str

    def __post_init__(self) -> None:
        if (
            release.VERSION.fullmatch(self.version) is None
            or not release.revision(self.sourceRevision)
            or not release.revision(self.sourceTree)
        ):
            raise ValueError("a loaded build needs version and source provenance")


@dataclass(frozen=True, slots=True)
class RuntimeIdentity:
    ideaHome: str
    profile: str
    pid: int
    processStartEpochMillis: int
    hostLifetime: str
    buildIdentity: RuntimeBuild
    autoReload: Literal["ENABLED", "DISABLED"]


@dataclass(frozen=True, slots=True)
class Target:
    ideaHome: str
    profile: str

    def __post_init__(self) -> None:
        if not self.ideaHome.startswith("/") or not self.profile.startswith("/"):
            raise ValueError("a selected target needs absolute IDEA and profile paths")


@dataclass(frozen=True, slots=True)
class Stopped:
    target: Target
    processes: tuple[ProcessIdentity, ...] = ()

    def __post_init__(self) -> None:
        if self.processes:
            raise ValueError("a stopped snapshot cannot contain a process")


@dataclass(frozen=True, slots=True)
class Loaded:
    process: ProcessIdentity
    identity: RuntimeIdentity
    contract: release.HostedContract

    def __post_init__(self) -> None:
        if (
            self.process.pid != self.identity.pid
            or self.process.processStartEpochMillis != self.identity.processStartEpochMillis
        ):
            raise ValueError("a loaded Host must match the captured process incarnation")


@dataclass(frozen=True, slots=True)
class LegacyHost:
    hostLifetime: str
    ideaHome: str
    ideBuild: str
    compatibilitySha256: str


class LegacyHostUnavailable(StrEnum):
    UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True, slots=True)
class Legacy:
    process: ProcessIdentity
    ideaHome: str
    profile: str
    contractDigest: str | None
    version: str | None
    host: LegacyHost | LegacyHostUnavailable = LegacyHostUnavailable.UNAVAILABLE


@dataclass(frozen=True, slots=True)
class Rejected:
    stage: Stage
    reason: Failure
    adapterCode: str | None = None
    type: Literal["REJECTED"] = "REJECTED"


type Snapshot = Stopped | Loaded | Legacy


def parse_process(value: object) -> ProcessIdentity | Failure:
    if not release.mapping(value) or set(value) != {"pid", "processStartEpochMillis", "executable"}:
        return Failure.HOST_RESPONSE_INVALID
    pid, started, executable = value["pid"], value["processStartEpochMillis"], value["executable"]
    if (
        not release.positive(pid)
        or not release.positive(started)
        or not isinstance(executable, str)
        or not executable.startswith("/")
    ):
        return Failure.HOST_RESPONSE_INVALID
    return ProcessIdentity(pid, started, executable)


def parse_runtime(value: object) -> RuntimeIdentity | Failure:
    if not release.mapping(value):
        return Failure.HOST_RESPONSE_INVALID
    home, profile, pid, started, lifetime, raw_build, auto = (
        value.get(key)
        for key in (
            "ideaHome",
            "profile",
            "pid",
            "processStartEpochMillis",
            "hostLifetime",
            "buildIdentity",
            "autoReload",
        )
    )
    if (
        not isinstance(home, str)
        or not isinstance(profile, str)
        or not home.startswith("/")
        or not profile.startswith("/")
        or not release.positive(pid)
        or not release.positive(started)
        or not isinstance(lifetime, str)
        or not 1 <= len(lifetime) <= 128
        or auto not in ("ENABLED", "DISABLED")
        or not release.mapping(raw_build)
    ):
        return Failure.HOST_RESPONSE_INVALID
    version, revision, tree = (
        raw_build.get(key) for key in ("version", "sourceRevision", "sourceTree")
    )
    if (
        not isinstance(version, str)
        or release.VERSION.fullmatch(version) is None
        or not release.revision(revision)
        or not release.revision(tree)
    ):
        return Failure.HOST_RESPONSE_INVALID
    return RuntimeIdentity(
        home,
        profile,
        pid,
        started,
        lifetime,
        RuntimeBuild(version, revision, tree),
        "ENABLED" if auto == "ENABLED" else "DISABLED",
    )


def parse_inspection(value: object, home: str, profile: str) -> Snapshot | Rejected:
    failure = Rejected(Stage.PREPARATION, Failure.HOST_RESPONSE_INVALID)
    if not release.mapping(value):
        return failure
    if value.get("type") == "REJECTED":
        code = value.get("reason")
        return Rejected(
            Stage.PREPARATION, Failure.HOST_REJECTED, code if isinstance(code, str) else None
        )
    processes = value.get("processes")
    if value.get("type") != "INSPECTED" or not release.sequence(processes):
        return failure
    parsed = [parse_process(item) for item in processes]
    if not parsed:
        return Stopped(Target(home, profile))
    if len(parsed) != 1:
        return Rejected(Stage.PREPARATION, Failure.TARGET_AMBIGUOUS)
    process = parsed[0]
    if isinstance(process, Failure):
        return failure
    result = value.get("result")
    if release.mapping(result) and result.get("type") == "inspected":
        identity = parse_runtime(result.get("runtimeIdentity"))
        compatibility = result.get("compatibility")
        if isinstance(identity, RuntimeIdentity) and release.mapping(compatibility):
            contract = release.parse_contract(compatibility.get("hostedContract"))
            if (
                not isinstance(contract, release.Failure)
                and identity.ideaHome == home
                and identity.profile == profile
                and identity.pid == process.pid
                and identity.processStartEpochMillis == process.processStartEpochMillis
                and identity.hostLifetime == result.get("host")
            ):
                return Loaded(process, identity, contract)
    legacy = value.get("legacyProfile")
    if (
        release.mapping(legacy)
        and legacy.get("ideaHome") == home
        and legacy.get("profile") == profile
        and parse_process(legacy.get("process")) == process
    ):
        digest, version = None, None
        host: LegacyHost | LegacyHostUnavailable = LegacyHostUnavailable.UNAVAILABLE
        if release.mapping(result) and result.get("type") == "inspected":
            captured = parse_legacy_host(result, home)
            if isinstance(captured, Rejected):
                return captured
            host = captured
            compatibility = result.get("compatibility")
            if release.mapping(compatibility):
                candidate_version = compatibility.get("hostedPluginVersion")
                if isinstance(candidate_version, str):
                    version = candidate_version
                # The prior Host owns this opaque contract. Recovery compares its captured bytes.
                digest = hashlib.sha256(
                    json.dumps(
                        compatibility.get("hostedContract"), sort_keys=True, separators=(",", ":")
                    ).encode()
                ).hexdigest()
        return Legacy(process, home, profile, digest, version, host)
    return Rejected(Stage.PREPARATION, Failure.OWNERSHIP_UNPROVEN)


def parse_legacy_host(value: object, home: str) -> LegacyHost | Rejected:
    if release.mapping(value) and value.get("type") == "inspected":
        host, captured_home, build, compatibility = (
            value.get(key) for key in ("host", "home", "build", "compatibility")
        )
        if (
            isinstance(host, str)
            and 1 <= len(host) <= 128
            and captured_home == home
            and isinstance(build, str)
            and 1 <= len(build) <= 128
            and release.mapping(compatibility)
            and value.get("runtimeIdentity") is None
        ):
            return LegacyHost(
                host,
                home,
                build,
                hashlib.sha256(
                    json.dumps(compatibility, sort_keys=True, separators=(",", ":")).encode()
                ).hexdigest(),
            )
    return Rejected(Stage.RE_ADMISSION, Failure.OWNERSHIP_UNPROVEN)


def readmit_inspection(value: object, prior: Snapshot) -> Snapshot | Rejected:
    if isinstance(prior, Legacy) and isinstance(prior.host, LegacyHost):
        if release.mapping(value) and value.get("type") == "INSPECTED":
            processes = value.get("processes")
            if (
                release.sequence(processes)
                and len(processes) == 1
                and parse_process(processes[0]) == prior.process
                and parse_legacy_host(value.get("result"), prior.ideaHome) == prior.host
                and value.get("legacyProfile") is None
            ):
                # The same process and Host lifetime preserve the earlier attached profile proof.
                return prior
    else:
        target = (
            prior.target
            if isinstance(prior, Stopped)
            else Target(prior.identity.ideaHome, prior.identity.profile)
            if isinstance(prior, Loaded)
            else Target(prior.ideaHome, prior.profile)
        )
        parsed = parse_inspection(value, target.ideaHome, target.profile)
        if isinstance(parsed, Rejected):
            return parsed
        if parsed == prior:
            return prior
    return Rejected(Stage.RE_ADMISSION, Failure.CONCURRENT_CHANGE)


def choose_strategy(
    snapshot: Snapshot, reload_compatible: bool, restart: bool
) -> Strategy | Rejected:
    match snapshot:
        case Stopped():
            return Strategy.STOPPED
        case Loaded(identity=identity) if reload_compatible and identity.autoReload == "ENABLED":
            return Strategy.HOT_RELOAD
        case Loaded() | Legacy():
            return (
                Strategy.NORMAL_RESTART
                if restart
                else Rejected(Stage.PREPARATION, Failure.RESTART_REQUIRED)
            )


def matches_selected(
    snapshot: Snapshot, identity: release.BuildIdentity, contract: release.HostedContract
) -> bool:
    return (
        isinstance(snapshot, Loaded)
        and snapshot.identity.buildIdentity
        == RuntimeBuild(identity.version, identity.buildRevision, identity.sourceTree)
        and snapshot.contract == contract
    )


def profile_options(previous: bytes) -> bytes:
    kept = [
        line
        for line in previous.splitlines(keepends=True)
        if not line.strip().startswith(
            (
                b"-Didea.auto.reload.plugins=",
                b"-Didea.auto.reload.plugins\n",
                b"-Didea.auto.reload.plugins\r",
            )
        )
        and line.strip() != b"-Didea.auto.reload.plugins"
    ]
    content = b"".join(kept)
    return (
        content
        + (b"\n" if content and not content.endswith(b"\n") else b"")
        + b"-Didea.auto.reload.plugins=true\n"
    )


@dataclass(frozen=True, slots=True)
class InstalledFiles:
    identity: release.BuildIdentity
    controlSha256: str
    hostSha256: str
    contract: release.HostedContract
    target: Target

    def __post_init__(self) -> None:
        if not all(
            release.DIGEST.fullmatch(digest) for digest in (self.controlSha256, self.hostSha256)
        ):
            raise ValueError("installed files need both admitted artifact digests")


@dataclass(frozen=True, slots=True)
class Activated:
    files: InstalledFiles
    hosts: tuple[Loaded, ...]
    notices: tuple[journal.ManagedNotice, ...]
    nextAction: str = "Start fresh agent sessions."
    type: Literal["ACTIVATED"] = "ACTIVATED"

    def __post_init__(self) -> None:
        if not self.hosts or not all(
            matches_selected(host, self.files.identity, self.files.contract)
            and Target(host.identity.ideaHome, host.identity.profile) == self.files.target
            for host in self.hosts
        ):
            raise ValueError("activation needs every selected loaded Host")


@dataclass(frozen=True, slots=True)
class InstalledNotRunning:
    files: InstalledFiles
    stopped: Stopped
    notices: tuple[journal.ManagedNotice, ...]
    nextAction: str = "Start IntelliJ IDEA, then start fresh agent sessions."
    type: Literal["INSTALLED_NOT_RUNNING"] = "INSTALLED_NOT_RUNNING"

    def __post_init__(self) -> None:
        if self.stopped.processes or self.stopped.target != self.files.target:
            raise ValueError("stopped installation needs a stopped-IDE snapshot")


@dataclass(frozen=True, slots=True)
class RestoredComposition:
    previous: journal.PreviousPayload
    host: journal.ManagedHostPrevious
    runtime: Snapshot


@dataclass(frozen=True, slots=True)
class RolledBack:
    transaction: str
    failure: Rejected
    restored: RestoredComposition
    type: Literal["ROLLED_BACK"] = "ROLLED_BACK"


@dataclass(frozen=True, slots=True)
class RecoveryRequired:
    transaction: str
    failure: Rejected
    recovery: Rejected
    type: Literal["RECOVERY_REQUIRED"] = "RECOVERY_REQUIRED"


@dataclass(frozen=True, slots=True)
class Planned:
    identity: release.BuildIdentity
    strategy: Strategy
    type: Literal["PLANNED"] = "PLANNED"
    operation: Literal["installation.plan"] = "installation.plan"


type Outcome = Activated | InstalledNotRunning | Rejected | RolledBack | RecoveryRequired | Planned
