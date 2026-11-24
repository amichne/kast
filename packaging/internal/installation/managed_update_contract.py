"""Pure admission and terminal evidence for one managed installation transaction."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from enum import StrEnum
from typing import Literal


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
    RELOAD_SIGNAL_REJECTED = "RELOAD_SIGNAL_REJECTED"
    FILESYSTEM_REJECTED = "FILESYSTEM_REJECTED"
    STORAGE_EXHAUSTED = "STORAGE_EXHAUSTED"
    CONTROL_REJECTED = "CONTROL_REJECTED"
    HOST_REPLACEMENT_REJECTED = "HOST_REPLACEMENT_REJECTED"
    RECOVERY_UNPROVEN = "RECOVERY_UNPROVEN"
    CHILD_REJECTED = "CHILD_REJECTED"
    CHILD_TIMEOUT = "CHILD_TIMEOUT"
    CANCELLED = "CANCELLED"
    PLATFORM_UNAVAILABLE = "PLATFORM_UNAVAILABLE"
    # The installed records are not ones this installer admits: only a reset replaces them.
    PRIOR_INSTALLATION_UNSUPPORTED = "PRIOR_INSTALLATION_UNSUPPORTED"
    # State admission: a check names the entries it refused through StateEntries.
    UNOWNED_STATE_ENTRY = "UNOWNED_STATE_ENTRY"
    MISSING_STATE_ENTRY = "MISSING_STATE_ENTRY"
    UNSETTLED_INVOCATIONS = "UNSETTLED_INVOCATIONS"
    UNRETIRED_WORKSPACE_ENTRY = "UNRETIRED_WORKSPACE_ENTRY"
    STATE_ENTRY_LIMIT_EXCEEDED = "STATE_ENTRY_LIMIT_EXCEEDED"
    # An exception no check owns: a defect in the installer, never an ownership verdict.
    INSTALLATION_DEFECT = "INSTALLATION_DEFECT"


class Stage(StrEnum):
    PREPARATION = "PREPARATION"
    RE_ADMISSION = "RE_ADMISSION"
    JOURNAL = "JOURNAL"
    FENCE = "FENCE"
    REQUEST_RETIREMENT = "REQUEST_RETIREMENT"
    HOST_SETTLEMENT = "HOST_SETTLEMENT"
    PROFILE = "PROFILE"
    CONTROL_REPLACEMENT = "CONTROL_REPLACEMENT"
    CONTROL_ARCHIVE = "CONTROL_ARCHIVE"
    HOST_REPLACEMENT = "HOST_REPLACEMENT"
    HOST_ACTIVATION = "HOST_ACTIVATION"
    RECEIPTS = "RECEIPTS"
    FINALIZATION = "FINALIZATION"
    RECOVERY = "RECOVERY"


class StateComponent(StrEnum):
    """The admission check that owns a state directory.

    Entries are relative to the installation payload, except WORKSPACE_STORE entries,
    which are relative to the workspace store beside the installation root.
    """

    INSTALLATION_LAYOUT = "kast-installation-layout"
    INSTALLATION_STATE = "kast-installation-state"
    INVOCATION_STATE = "kast-invocation-state"
    SEMANTIC_COORDINATOR_STATE = "kast-semantic-coordinator-state"
    CLIENT_CONFIGURATION = "kast-client-configuration"
    WORKSPACE_CONFIGURATION = "kast-workspace-configuration"
    WORKSPACE_STORE = "kast-workspace-store"
    CONTROL_PAYLOAD_TREE = "kast-control-payload-tree"


MAXIMUM_REPORTED_ENTRIES = 8
MAXIMUM_REPORTED_NAME_CHARACTERS = 64


def reported_entries(names: Iterable[str]) -> tuple[str, ...]:
    """Sorted, bounded entry names: enough to identify the cause without unbounded output."""
    ordered = sorted({name[:MAXIMUM_REPORTED_NAME_CHARACTERS] for name in names})
    return tuple(ordered[:MAXIMUM_REPORTED_ENTRIES])


@dataclass(frozen=True, slots=True)
class StateEntries:
    component: StateComponent
    entries: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class StateRejection:
    """The one observation a rejecting state check emits; names only, never contents."""

    reason: Failure
    component: StateComponent
    entries: tuple[str, ...]
    outcome: Literal["REJECTED"] = "REJECTED"


@dataclass(frozen=True, slots=True)
class Rejected:
    stage: Stage
    reason: Failure
    adapterCode: str | None = None
    state: StateEntries | None = None
    type: Literal["REJECTED"] = "REJECTED"


@dataclass(frozen=True, slots=True)
class RecoveryRequired:
    transaction: str
    failure: Rejected
    recovery: Rejected
    type: Literal["RECOVERY_REQUIRED"] = "RECOVERY_REQUIRED"
