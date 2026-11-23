"""Canonical values for the existing managed update journal and installed build identity."""

import os
import re
import sys
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Literal, Optional, Union

# Imported before the managed adapters, so manifest-owned source gains no bytecode files.
sys.dont_write_bytecode = True


class Failure(str, Enum):
    OWNERSHIP = "ANCHOR_OWNERSHIP_UNPROVEN"
    CONTROL_LIMIT = "CONTROL_LIMIT_EXCEEDED"
    RECEIPT = "RECEIPT_REJECTED"
    FILESYSTEM = "FILESYSTEM_REJECTED"
    RETIREMENT = "RETIREMENT_UNPROVEN"
    EVIDENCE = "UNRESOLVED_STATE_PRESERVED"
    RESTART = "IDE_RESTART_REQUIRED"
    PLUGIN = "PLUGIN_OWNERSHIP_UNPROVEN"


class Rejected(Exception):
    # entry: the rejected tree entry, relative to the captured tree root; never its contents.
    def __init__(self, failure: Failure, entry: Optional[str] = None) -> None:
        self.failure = failure
        self.entry = entry


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


class ManagedPhase(str, Enum):
    PREPARED = "PREPARED"
    FENCED = "FENCED"
    SETTLED = "SETTLED"
    CONTROL_COMMITTED = "CONTROL_COMMITTED"
    ACTIVATION_PROVEN = "ACTIVATION_PROVEN"


def _phase(value: object) -> ManagedPhase:
    if isinstance(value, str):
        try:
            return ManagedPhase(value)
        except ValueError:
            pass
    raise Rejected(Failure.RECEIPT)


def _tag(value: object, expected: str) -> None:
    if value != expected:
        raise Rejected(Failure.RECEIPT)


@dataclass(frozen=True)
class ProductReplacement:
    """Product selector publication uses the same Control reservation and rollback slots."""

    management: Union[ManagedFileAbsent, ManagedFilePresent]
    publicExecutable: Union[ManagedFileAbsent, ManagedFilePresent]
    selectionSha256: str
    previousManifestSha256: Optional[str]
    phase: ManagedPhase = ManagedPhase.PREPARED
    fence: Optional[Identity] = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "phase", _phase(self.phase))


@dataclass(frozen=True)
class Replacement:
    schemaVersion: int
    stage: ReplacementStage
    installation: str
    installationIdentity: Identity
    previous: Union[NoPrevious, PhysicalPrevious]
    reservation: Optional[ManagedReservation] = None
    product: Optional[ProductReplacement] = None


@dataclass(frozen=True)
class ManagedTreeWitness:
    identity: Identity
    inventorySha256: str


@dataclass(frozen=True)
class ManagedShutdownFence:
    installationRoot: str
    schemaVersion: int


ManagedFilePreimage = Union[ManagedFileAbsent, ManagedFilePresent]
PreviousPayload = Union[NoPrevious, PhysicalPrevious]


def admit_replacement(value: object) -> Replacement:
    """Accept only the existing decoder's canonical runtime class."""
    if not isinstance(value, Replacement):
        raise Rejected(Failure.RECEIPT)
    return value
