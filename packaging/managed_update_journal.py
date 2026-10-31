"""Canonical values for the existing managed update journal and installed build identity."""

import os
import re
import sys
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Literal, Optional, Union

# Imported before the managed adapters, so manifest-owned source gains no bytecode files.
sys.dont_write_bytecode = True


class Failure(str, Enum):
    OWNERSHIP = "ANCHOR_OWNERSHIP_UNPROVEN"
    RECEIPT = "RECEIPT_REJECTED"
    FILESYSTEM = "FILESYSTEM_REJECTED"
    RETIREMENT = "RETIREMENT_UNPROVEN"
    EVIDENCE = "UNRESOLVED_STATE_PRESERVED"
    RESTART = "IDE_RESTART_REQUIRED"
    PLUGIN = "PLUGIN_OWNERSHIP_UNPROVEN"


class Rejected(Exception):
    def __init__(self, failure: Failure) -> None:
        self.failure = failure


@dataclass(frozen=True)
class Identity:
    device: int
    inode: int
    owner: int

    @staticmethod
    def observe(path: Path) -> "Identity":
        value = path.lstat()
        if value.st_uid != os.getuid():
            raise Rejected(Failure.OWNERSHIP)
        return Identity(value.st_dev, value.st_ino, value.st_uid)


class ReplacementStage(str, Enum):
    PREPARED = "PREPARED"
    COMMITTED = "PAYLOAD_COMMITTED"
    FINALIZING = "FINALIZING"
    RECOVERY_REQUIRED = "RECOVERY_REQUIRED"


@dataclass(frozen=True)
class NoPrevious:
    type: str


@dataclass(frozen=True)
class PhysicalPrevious:
    type: str
    installation: str
    identity: Identity
    payload: str
    recovery: str


@dataclass(frozen=True)
class LegacyPrevious:
    type: str
    installation: str
    identity: Identity
    selector: str
    target: str
    recovery: str


@dataclass(frozen=True)
class ManagedReservation:
    transaction: str
    staged: str
    controlSha256: str
    version: str
    manifestSha256: str


@dataclass(frozen=True)
class ManagedFileAbsent:
    destination: str
    type: Literal["ABSENT"]

    def __post_init__(self) -> None:
        _tag(self.type, "ABSENT")


@dataclass(frozen=True)
class ManagedFilePresent:
    destination: str
    preimage: str
    sha256: str
    mode: int
    type: Literal["PRESENT"]

    def __post_init__(self) -> None:
        _tag(self.type, "PRESENT")


@dataclass(frozen=True)
class ManagedHostAbsent:
    type: str


@dataclass(frozen=True)
class ManagedHostPresent:
    identity: Identity
    inventorySha256: str
    receipt: str
    receiptIdentity: Identity
    receiptSha256: str
    type: str


@dataclass(frozen=True)
class ManagedHostExistingLock:
    identity: Identity
    type: Literal["EXISTING"] = "EXISTING"

    def __post_init__(self) -> None:
        _tag(self.type, "EXISTING")


@dataclass(frozen=True)
class ManagedHostStagedLock:
    identity: Identity
    staged: str
    retainedIdentity: Optional[Identity]
    type: Literal["STAGED"] = "STAGED"

    def __post_init__(self) -> None:
        _tag(self.type, "STAGED")


@dataclass(frozen=True)
class ManagedHostReplacement:
    pluginRoot: str
    pluginRootIdentity: Optional[Identity]
    retainedRootIdentity: Optional[Identity]
    destination: str
    candidate: str
    candidateIdentity: Identity
    candidateInventorySha256: str
    backup: str
    previous: Union[ManagedHostAbsent, ManagedHostPresent]
    version: str
    sha256: str
    lock: Optional[Union[ManagedHostExistingLock, ManagedHostStagedLock]] = None


@dataclass(frozen=True)
class ManagedProfileMissing:
    path: str
    type: Literal["MISSING"]

    def __post_init__(self) -> None:
        _tag(self.type, "MISSING")


@dataclass(frozen=True)
class ManagedProfileCreated:
    path: str
    identity: Identity
    type: Literal["CREATED"]

    def __post_init__(self) -> None:
        _tag(self.type, "CREATED")


@dataclass(frozen=True)
class BuildIdentity:
    releaseTag: str
    version: str
    buildRevision: str
    sourceTree: str
    mainRevision: Optional[str] = None

    def __post_init__(self) -> None:
        if (
            not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", self.version)
            or any(
                not re.fullmatch(r"[0-9a-f]{40}", value) or value == "0" * 40
                for value in (self.buildRevision, self.sourceTree)
            )
            or (self.mainRevision is None and self.releaseTag != "v" + self.version)
            or (
                self.mainRevision is not None
                and (
                    self.releaseTag != "developer-v" + self.version
                    or not re.fullmatch(r"[0-9a-f]{40}", self.mainRevision)
                    or self.mainRevision == "0" * 40
                )
            )
        ):
            raise Rejected(Failure.RECEIPT)


@dataclass(frozen=True)
class TrackStable:
    type: str = "TrackStable"

    def __post_init__(self) -> None:
        if self.type != "TrackStable":
            raise Rejected(Failure.RECEIPT)


@dataclass(frozen=True)
class TrackMain:
    type: str = "TrackMain"

    def __post_init__(self) -> None:
        if self.type != "TrackMain":
            raise Rejected(Failure.RECEIPT)


@dataclass(frozen=True)
class PinnedBuild:
    identity: BuildIdentity
    type: str = "PinnedBuild"

    def __post_init__(self) -> None:
        if self.type != "PinnedBuild":
            raise Rejected(Failure.RECEIPT)


ManagedBuildIdentity = BuildIdentity
ManagedTrackStable = TrackStable
ManagedTrackMain = TrackMain
ManagedPinnedBuild = PinnedBuild
UpdatePolicy = Union[TrackStable, TrackMain, PinnedBuild]


@dataclass(frozen=True)
class ManagedBinding:
    identity: ManagedBuildIdentity
    policy: Union[ManagedTrackStable, ManagedTrackMain, ManagedPinnedBuild]

    def __post_init__(self) -> None:
        if (
            (isinstance(self.policy, ManagedTrackStable) and self.identity.mainRevision is not None)
            or (isinstance(self.policy, ManagedTrackMain) and self.identity.mainRevision is None)
            or (
                isinstance(self.policy, ManagedPinnedBuild)
                and self.policy.identity != self.identity
            )
        ):
            raise Rejected(Failure.RECEIPT)


class ManagedNotice(str, Enum):
    HARNESS_REFRESH_REQUIRED = "HARNESS_REFRESH_REQUIRED"


class ManagedPhase(str, Enum):
    PREPARED = "PREPARED"
    FENCED = "FENCED"
    SETTLED = "SETTLED"
    CONTROL_COMMITTED = "CONTROL_COMMITTED"
    HOST_COMMITTED = "HOST_COMMITTED"
    ACTIVATION_PROVEN = "ACTIVATION_PROVEN"
    RESTORING = "RESTORING"
    RESTORED = "RESTORED"


class ManagedStrategy(str, Enum):
    STOPPED = "STOPPED"
    HOT_RELOAD = "HOT_RELOAD"
    NORMAL_RESTART = "NORMAL_RESTART"


def _phase(value: object) -> ManagedPhase:
    if isinstance(value, str):
        try:
            return ManagedPhase(value)
        except ValueError:
            pass
    raise Rejected(Failure.RECEIPT)


def _strategy(value: object) -> ManagedStrategy:
    if isinstance(value, str):
        try:
            return ManagedStrategy(value)
        except ValueError:
            pass
    raise Rejected(Failure.RECEIPT)


def _tag(value: object, expected: str) -> None:
    if value != expected:
        raise Rejected(Failure.RECEIPT)


def _profile_directories() -> list[Union[ManagedProfileMissing, ManagedProfileCreated]]:
    return []


def _notices() -> list[ManagedNotice]:
    return []


@dataclass(frozen=True)
class ManagedComposition:
    phase: ManagedPhase
    strategy: ManagedStrategy
    ideaHome: str
    profile: str
    selection: ManagedFilePresent
    management: Union[ManagedFileAbsent, ManagedFilePresent]
    publicExecutable: Union[ManagedFileAbsent, ManagedFilePresent]
    profileOptions: Union[ManagedFileAbsent, ManagedFilePresent]
    host: ManagedHostReplacement
    priorRuntime: Union[ManagedFileAbsent, ManagedFilePresent]
    profileDirectories: list[Union[ManagedProfileMissing, ManagedProfileCreated]] = field(
        default_factory=_profile_directories
    )
    binding: Optional[ManagedBinding] = None
    notices: list[ManagedNotice] = field(default_factory=_notices)
    priorRuntimeJson: Optional[str] = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "phase", _phase(self.phase))
        object.__setattr__(self, "strategy", _strategy(self.strategy))


@dataclass(frozen=True)
class Replacement:
    schemaVersion: int
    stage: ReplacementStage
    installation: str
    installationIdentity: Identity
    previous: Union[NoPrevious, PhysicalPrevious, LegacyPrevious]
    reservation: Optional[ManagedReservation] = None
    composition: Optional[ManagedComposition] = None


class ManagedControlResult(str, Enum):
    RESTORED = "CONTROL_RESTORED"
    SEALED = "CONTROL_SEALED"


@dataclass(frozen=True)
class ManagedTreeWitness:
    identity: Identity
    inventorySha256: str


@dataclass(frozen=True)
class ManagedShutdownFence:
    installationRoot: str
    schemaVersion: int


ManagedFilePreimage = Union[ManagedFileAbsent, ManagedFilePresent]
ManagedProfileDirectory = Union[ManagedProfileMissing, ManagedProfileCreated]
ManagedHostPrevious = Union[ManagedHostAbsent, ManagedHostPresent]
PreviousPayload = Union[NoPrevious, PhysicalPrevious, LegacyPrevious]


def admit_replacement(value: object) -> Replacement:
    """Accept only the existing decoder's canonical runtime class."""
    if not isinstance(value, Replacement):
        raise Rejected(Failure.RECEIPT)
    return value


def admit_host_replacement(value: object) -> ManagedHostReplacement:
    if not isinstance(value, ManagedHostReplacement):
        raise Rejected(Failure.RECEIPT)
    return value


def managed_parts(value: Replacement) -> tuple[ManagedReservation, ManagedComposition]:
    if value.reservation is None or value.composition is None:
        raise Rejected(Failure.RECEIPT)
    return value.reservation, value.composition
