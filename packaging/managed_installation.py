#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["defusedxml==0.7.1", "types-defusedxml==0.7.0.20260504"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Candidate bootstrap and recoverable composition coordinator."""

from __future__ import annotations

import argparse
import ctypes
import fcntl
import hashlib
import importlib.util
import json
import os
import platform
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from collections.abc import Iterator
from contextlib import ExitStack, contextmanager
from dataclasses import asdict, dataclass, is_dataclass, replace
from enum import Enum, StrEnum
from pathlib import Path
from types import ModuleType
from typing import Literal, Protocol

import managed_release as release
import managed_update_contract as contract
import managed_update_journal as journal_types
import reload_hosted


def module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError("installation adapter unavailable")
    owner = importlib.util.module_from_spec(spec)
    sys.modules[name] = owner
    spec.loader.exec_module(owner)
    return owner


HOST = module("kast_managed_host", Path(__file__).with_name("host-installation.py"))
RECOVERY = module("kast_managed_recovery", Path(__file__).with_name("installation-recovery.py"))
LIFECYCLE = module("kast_managed_lifecycle", Path(__file__).with_name("installation-lifecycle.py"))


@dataclass(frozen=True, slots=True)
class SelectedIdea:
    home: Path
    profile: Path
    java: Path
    line: str
    build: str

    @property
    def target(self) -> contract.Target:
        return contract.Target(str(self.home), str(self.profile))


@dataclass(frozen=True, slots=True)
class InspectRequest:
    ideaHome: str
    profile: str
    legacyProfileInspection: Literal["ATTACH", "REJECT_UNPROVEN"]
    type: Literal["INSPECT"] = "INSPECT"


@dataclass(frozen=True, slots=True)
class HostActionRequest:
    ideaHome: str
    profile: str
    host: str
    transaction: str
    type: Literal["SETTLE", "STOP", "RESUME"]


@dataclass(frozen=True, slots=True)
class StartRequest:
    ideaHome: str
    profile: str
    type: Literal["START"] = "START"


@dataclass(frozen=True, slots=True)
class LegacyStopRequest:
    ideaHome: str
    profile: str
    process: contract.ProcessIdentity
    transaction: str
    type: Literal["LEGACY_STOP"] = "LEGACY_STOP"


@dataclass(frozen=True, slots=True)
class ShutdownRequest:
    installationRoot: str
    schemaVersion: int = 1


@dataclass(frozen=True, slots=True)
class SelectionBinding:
    identity: release.BuildIdentity
    policy: release.UpdatePolicy


@dataclass(frozen=True, slots=True)
class SelectionRejected:
    reason: release.Failure
    stage: release.Stage
    requested: str
    platform: str
    selectable: tuple[str, ...]
    type: Literal["REJECTED"] = "REJECTED"


@dataclass(frozen=True, slots=True)
class Registration:
    connection: Literal["CODEX_MCP", "CODEX_APP_SERVER", "COPILOT", "PI"]
    destination: str
    payloadSha256: str
    directory: str | None = None


@dataclass(frozen=True, slots=True)
class GuidanceFile:
    path: str
    sha256: str


@dataclass(frozen=True, slots=True)
class Guidance:
    harness: Literal["CODEX", "COPILOT", "PI"]
    destination: str
    files: tuple[GuidanceFile, ...]


@dataclass(frozen=True, slots=True)
class ManagementReceipt:
    schemaVersion: int
    installationRoot: str
    executable: str
    executableSha256: str
    channel: Literal["STABLE", "DEVELOPER"]
    registrations: tuple[Registration, ...]
    updatePolicy: release.UpdatePolicy
    installedBuild: release.BuildIdentity | None
    guidance: tuple[Guidance, ...] = ()


@dataclass(slots=True)
class InterruptState:
    exit_code: int | None = None


@dataclass(slots=True)
class HostLease:
    stack: ExitStack
    record: journal_types.ManagedHostReplacement


class ChildRunner(Protocol):
    def __call__(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]: ...


def run_child(
    arguments: list[str], environment: dict[str, str], timeout: float
) -> subprocess.CompletedProcess[bytes]:
    with subprocess.Popen(
        arguments,
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ) as child:
        try:
            stdout, stderr = child.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            child.kill()
            _ = child.communicate()
            raise
        except KeyboardInterrupt:
            # A partial native effect must finish or stop before the journal owner restores files.
            child.terminate()
            try:
                _ = child.communicate(timeout=min(timeout, 5))
            except subprocess.TimeoutExpired:
                child.kill()
                _ = child.communicate()
            raise
        return subprocess.CompletedProcess(arguments, child.returncode, stdout, stderr)


@contextmanager
def management_lock(root: Path) -> Iterator[None]:
    if root.resolve() != root:
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    root.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(root / "management.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "a+b") as lock:
        attributes = os.fstat(lock.fileno())
        if not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid():
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


@dataclass(frozen=True, slots=True)
class StageEvidence:
    stage: contract.Stage
    outcome: Literal["STARTED", "COMPLETED", "REJECTED"]
    reason: contract.Failure | None = None


@dataclass(frozen=True, slots=True)
class Limits:
    settlement: float
    activation: float
    child: float


class StageRejected(Exception):
    def __init__(self, failure: contract.Rejected) -> None:
        self.failure = failure
        super().__init__(failure.reason.value)


def encoded(value: object) -> bytes:
    if not is_dataclass(value) or isinstance(value, type):
        raise TypeError("management output needs a typed document")
    return (json.dumps(asdict(value), separators=(",", ":")) + "\n").encode()


def read_record(path: Path, limit: int = 65_536) -> bytes:
    if path.resolve() != path:
        raise OSError("record path rejected")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if path.is_symlink() or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise OSError("record owner rejected")
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise OSError("record limit exceeded")
    return data


def text_field(value: object) -> str:
    if not isinstance(value, str) or len(value) > 4096:
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    return value


def parse_management(raw: bytes) -> ManagementReceipt:
    value = release.document(raw)
    if (
        not release.mapping(value)
        or not {
            "schemaVersion",
            "installationRoot",
            "executable",
            "executableSha256",
            "channel",
            "registrations",
        }
        <= set(value)
        or set(value)
        - {
            "schemaVersion",
            "installationRoot",
            "executable",
            "executableSha256",
            "channel",
            "registrations",
            "updatePolicy",
            "installedBuild",
            "guidance",
        }
    ):
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    schema, channel, registrations, guidance = (
        value["schemaVersion"],
        value["channel"],
        value["registrations"],
        value.get("guidance", []),
    )
    if (
        type(schema) is not int
        or schema not in (1, 2)
        or channel not in ("STABLE", "DEVELOPER")
        or not release.sequence(registrations)
        or not release.sequence(guidance)
    ):
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    parsed_registrations: list[Registration] = []
    for item in registrations:
        if not release.mapping(item):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
        if schema == 1:
            if set(item) != {"harness", "destination", "payloadSha256"} or item["harness"] not in (
                "CODEX",
                "COPILOT",
                "PI",
            ):
                raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
            connection = "CODEX_MCP" if item["harness"] == "CODEX" else item["harness"]
        else:
            if not {"connection", "destination", "payloadSha256"} <= set(item) or set(item) - {
                "connection",
                "destination",
                "payloadSha256",
                "directory",
            }:
                raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
            connection = item["connection"]
        if connection not in ("CODEX_MCP", "CODEX_APP_SERVER", "COPILOT", "PI"):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
        directory = item.get("directory")
        parsed_registrations.append(
            Registration(
                connection,
                text_field(item["destination"]),
                text_field(item["payloadSha256"]),
                None if directory is None else text_field(directory),
            )
        )
    parsed_guidance: list[Guidance] = []
    for item in guidance:
        if (
            not release.mapping(item)
            or set(item) != {"harness", "destination", "files"}
            or item["harness"] not in ("CODEX", "COPILOT", "PI")
            or not release.sequence(item["files"])
        ):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
        files: list[GuidanceFile] = []
        for entry in item["files"]:
            if not release.mapping(entry) or set(entry) != {"path", "sha256"}:
                raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
            files.append(GuidanceFile(text_field(entry["path"]), text_field(entry["sha256"])))
        parsed_guidance.append(
            Guidance(item["harness"], text_field(item["destination"]), tuple(files))
        )
    raw_build, raw_policy = value.get("installedBuild"), value.get("updatePolicy")
    build = None if raw_build is None else release.parse_identity(raw_build)
    if isinstance(build, release.Failure):
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    if raw_policy is None:
        policy: release.UpdatePolicy = (
            release.TrackStable() if channel == "STABLE" else release.TrackMain()
        )
    elif release.mapping(raw_policy) and raw_policy == {"type": "TrackStable"}:
        policy = release.TrackStable()
    elif release.mapping(raw_policy) and raw_policy == {"type": "TrackMain"}:
        policy = release.TrackMain()
    elif (
        release.mapping(raw_policy)
        and set(raw_policy) == {"type", "identity"}
        and raw_policy["type"] == "PinnedBuild"
    ):
        pinned = release.parse_identity(raw_policy["identity"])
        if isinstance(pinned, release.Failure):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
        policy = release.PinnedBuild(pinned)
    else:
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    return ManagementReceipt(
        2,
        text_field(value["installationRoot"]),
        text_field(value["executable"]),
        text_field(value["executableSha256"]),
        channel,
        tuple(parsed_registrations),
        policy,
        build,
        tuple(parsed_guidance),
    )


def selected_management(
    adapter: NativeComposition, core: journal_types.ManagedComposition
) -> ManagementReceipt:
    if core.binding is None:
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    preimage = core.management
    previous = (
        parse_management(read_record(Path(preimage.preimage)))
        if isinstance(preimage, journal_types.ManagedFilePresent)
        else None
    )
    return ManagementReceipt(
        2,
        str(adapter.root),
        core.publicExecutable.destination,
        release.file_digest(adapter.control / "share/kast/libexec/kast-management"),
        "STABLE" if core.binding.identity.mainRevision is None else "DEVELOPER",
        previous.registrations if previous else (),
        core.binding.policy,
        core.binding.identity,
        previous.guidance if previous else (),
    )


def emit(
    stage: contract.Stage,
    outcome: Literal["STARTED", "COMPLETED", "REJECTED"],
    reason: contract.Failure | None = None,
) -> None:
    print(encoded(StageEvidence(stage, outcome, reason)).decode().rstrip(), file=sys.stderr)


def reject(
    stage: contract.Stage, reason: contract.Failure, adapter: str | None = None
) -> StageRejected:
    emit(stage, "REJECTED", reason)
    return StageRejected(contract.Rejected(stage, reason, adapter))


def select_idea(explicit: str | None, home: Path, root: Path) -> SelectedIdea | contract.Rejected:
    candidates: list[Path] = []
    if explicit is not None:
        candidates = [Path(explicit)]
    else:
        selected = root / "installation/config/selected-ide.json"
        if selected.is_file() and not selected.is_symlink():
            saved = release.document(read_record(selected))
            saved_home = saved.get("home") if release.mapping(saved) else None
            if (
                release.mapping(saved)
                and saved.get("type") == "resolved"
                and isinstance(saved_home, str)
            ):
                candidates = [Path(saved_home)]
        if not candidates:
            for directory in (Path("/Applications"), home / "Applications"):
                if directory.is_dir():
                    candidates.extend(directory.glob("IntelliJ IDEA*.app"))
    physical = {
        str((path / "Contents" if path.suffix == ".app" else path).resolve())
        for path in candidates
        if path.is_absolute()
    }
    if len(physical) != 1:
        return contract.Rejected(
            contract.Stage.PREPARATION,
            contract.Failure.TARGET_AMBIGUOUS if physical else contract.Failure.IDEA_UNAVAILABLE,
        )
    idea = Path(physical.pop())
    try:
        document = release.document(read_record(idea / "Resources/product-info.json", 1_048_576))
        if not release.mapping(document):
            raise ValueError()
        data, build = document.get("dataDirectoryName"), document.get("buildNumber")
        if (
            not isinstance(data, str)
            or not data
            or data in {".", ".."}
            or Path(data).name != data
            or not isinstance(build, str)
            or not build.startswith("262.")
        ):
            raise ValueError()
        java = idea / "jbr/Contents/Home"
        if not os.access(java / "bin/java", os.X_OK) or not (idea / "plugins/Kotlin").is_dir():
            raise ValueError()
        return SelectedIdea(
            idea,
            home / "Library/Application Support/JetBrains" / data,
            java,
            build.split(".")[0],
            build,
        )
    except (OSError, ValueError):
        return contract.Rejected(contract.Stage.PREPARATION, contract.Failure.IDEA_UNAVAILABLE)


def read_limits(control: Path) -> Limits:
    document = release.document(
        read_record(control / "share/kast/configuration-schema.json", 1_048_576)
    )
    raw_limits = document.get("operationalLimits") if release.mapping(document) else None
    if not release.sequence(raw_limits):
        raise reject(contract.Stage.PREPARATION, contract.Failure.CONTROL_REJECTED)
    values: dict[str, int] = {}
    for item in raw_limits:
        key = item.get("key") if release.mapping(item) else None
        value = item.get("value") if release.mapping(item) else None
        if isinstance(key, str) and release.positive(value):
            if key in values:
                raise reject(contract.Stage.PREPARATION, contract.Failure.CONTROL_REJECTED)
            values[key] = value
    try:
        return Limits(
            values["installation.host.settlement_timeout"] / 1000,
            values["installation.host.activation_timeout"] / 1000,
            values["installation.retirement.child_timeout"] / 1000,
        )
    except KeyError:
        raise reject(contract.Stage.PREPARATION, contract.Failure.CONTROL_REJECTED) from None


class ChildProtocol(StrEnum):
    SUCCESS = "SUCCESS"
    MANAGEMENT_DOCUMENT = "MANAGEMENT_DOCUMENT"


class NativeComposition:
    def __init__(
        self,
        root: Path,
        home: Path,
        idea: SelectedIdea,
        control: Path,
        temporary: Path,
        limits: Limits,
        environment: dict[str, str],
        runner: ChildRunner = run_child,
        interrupt: InterruptState | None = None,
    ) -> None:
        self.root, self.home, self.idea, self.control, self.temporary, self.limits = (
            root,
            home,
            idea,
            control,
            temporary,
            limits,
        )
        self.environment = {
            key: value
            for key, value in environment.items()
            if key in {"PATH", "TMPDIR", "XDG_CONFIG_HOME"}
        }
        self.environment.update(
            {
                "HOME": str(home),
                "JAVA_HOME": str(idea.java),
                "JAVA": str(idea.java / "bin/java"),
                "JAVA_TOOL_OPTIONS": "'-Duser.home=" + str(home).replace("'", "'\"'\"'") + "'",
                "KAST_INSTALL_ROOT": str(root),
            }
        )
        self.stage = contract.Stage.PREPARATION
        self.runner = runner
        self.interrupt = interrupt if interrupt is not None else InterruptState()
        self.host_lease: HostLease | None = None

    @property
    def native(self) -> Path:
        return self.control / "share/kast/libexec/kast-management"

    def enter(self, stage: contract.Stage) -> None:
        self.stage = stage
        emit(stage, "STARTED")

    def child(
        self,
        arguments: list[str],
        extra: dict[str, str] | None = None,
        protocol: ChildProtocol = ChildProtocol.SUCCESS,
    ) -> bytes:
        try:
            result = self.runner(arguments, self.environment | (extra or {}), self.limits.child)
        except subprocess.TimeoutExpired:
            raise reject(self.stage, contract.Failure.CHILD_TIMEOUT) from None
        except OSError:
            raise reject(self.stage, contract.Failure.CHILD_REJECTED) from None
        if len(result.stdout) > 1_048_576:
            raise reject(self.stage, contract.Failure.CHILD_REJECTED)
        if result.returncode != 0:
            if protocol == ChildProtocol.MANAGEMENT_DOCUMENT:
                try:
                    document = release.document(result.stdout)
                except (ValueError, UnicodeError):
                    raise reject(self.stage, contract.Failure.CHILD_REJECTED) from None
                if release.mapping(document) and document.get("type") in {
                    "REJECTED",
                    "RECOVERY_REQUIRED",
                }:
                    return result.stdout
            raise reject(self.stage, contract.Failure.CHILD_REJECTED)
        return result.stdout

    def bridge(
        self, request: InspectRequest | HostActionRequest | StartRequest | LegacyStopRequest
    ) -> tuple[object, bytes]:
        path = self.temporary / ("host-" + uuid.uuid4().hex + ".json")
        _ = path.write_bytes(encoded(request))
        path.chmod(0o600)
        try:
            raw = self.child(
                [str(self.control / "share/kast/libexec/kast-service"), "managed-host", str(path)],
                protocol=ChildProtocol.MANAGEMENT_DOCUMENT,
            )
            document = release.document(raw)
            if release.mapping(document) and document.get("type") in (
                "REJECTED",
                "RECOVERY_REQUIRED",
            ):
                code = document.get("reason", document.get("failure"))
                result = document.get("result")
                if (
                    release.mapping(result)
                    and result.get("type") == "blocked"
                    and isinstance(result.get("reason"), str)
                ):
                    code = result["reason"]
                raise reject(
                    self.stage,
                    contract.Failure.HOST_REJECTED,
                    code if isinstance(code, str) else None,
                )
            return document, raw
        finally:
            path.unlink(missing_ok=True)

    def inspection_frame(self, attach: bool) -> tuple[object, bytes]:
        value, raw = self.bridge(
            InspectRequest(
                str(self.idea.home),
                str(self.idea.profile),
                "ATTACH" if attach else "REJECT_UNPROVEN",
            )
        )
        result = value.get("result") if release.mapping(value) else None
        blocked_reason = result.get("reason") if release.mapping(result) else None
        if (
            release.mapping(result)
            and result.get("type") == "blocked"
            and isinstance(blocked_reason, str)
        ):
            raise reject(self.stage, contract.Failure.HOST_REJECTED, blocked_reason)
        return value, raw

    def inspect(self, attach: bool = True) -> tuple[contract.Snapshot, bytes]:
        value, raw = self.inspection_frame(attach)
        parsed = contract.parse_inspection(value, str(self.idea.home), str(self.idea.profile))
        if isinstance(parsed, contract.Rejected):
            raise StageRejected(replace(parsed, stage=self.stage))
        return parsed, raw

    def readmit(self, prior: contract.Snapshot) -> contract.Snapshot:
        attach = isinstance(prior, contract.Legacy) and isinstance(
            prior.host, contract.LegacyHostUnavailable
        )
        value, _ = self.inspection_frame(attach)
        admitted = contract.readmit_inspection(value, prior)
        if isinstance(admitted, contract.Rejected):
            raise StageRejected(admitted)
        return admitted

    def host_action(
        self,
        snapshot: contract.Loaded,
        transaction: str,
        action: Literal["SETTLE", "STOP", "RESUME"],
    ) -> object:
        value, _ = self.bridge(
            HostActionRequest(
                str(self.idea.home),
                str(self.idea.profile),
                snapshot.identity.hostLifetime,
                transaction,
                action,
            )
        )
        if not release.mapping(value) or value.get("type") != "INSPECTED":
            raise reject(self.stage, contract.Failure.HOST_REJECTED)
        result = value.get("result")
        if action in ("STOP", "RESUME"):
            expected = "host_stop_requested" if action == "STOP" else "host_resumed"
            if (
                not release.mapping(result)
                or result.get("type") != expected
                or result.get("host") != snapshot.identity.hostLifetime
                or result.get("transaction") != transaction
            ):
                raise reject(self.stage, contract.Failure.HOST_RESPONSE_INVALID)
            if (
                action == "STOP"
                and contract.parse_runtime(result.get("runtimeIdentity")) != snapshot.identity
            ):
                raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
        return result

    def settle(self, snapshot: contract.Loaded, transaction: str) -> None:
        deadline = time.monotonic() + self.limits.settlement
        while True:
            result = self.host_action(snapshot, transaction, "SETTLE")
            if release.mapping(result) and result.get("type") == "host_settled":
                if (
                    result.get("host") != snapshot.identity.hostLifetime
                    or result.get("transaction") != transaction
                    or contract.parse_runtime(result.get("runtimeIdentity")) != snapshot.identity
                ):
                    raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
                return
            if not release.mapping(result) or result.get("type") != "host_settling":
                raise reject(self.stage, contract.Failure.HOST_REJECTED)
            if (
                result.get("host") != snapshot.identity.hostLifetime
                or result.get("transaction") != transaction
            ):
                raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
            if time.monotonic() >= deadline:
                raise reject(self.stage, contract.Failure.HOST_SETTLEMENT_TIMEOUT)
            time.sleep(0.1)

    def stop(self, snapshot: contract.Loaded | contract.Legacy, transaction: str) -> None:
        if isinstance(snapshot, contract.Loaded):
            self.settle(snapshot, transaction)
            _ = self.host_action(snapshot, transaction, "STOP")
        else:
            value, _ = self.bridge(
                LegacyStopRequest(
                    str(self.idea.home), str(self.idea.profile), snapshot.process, transaction
                )
            )
            if not release.mapping(value) or value.get("type") != "LEGACY_STOP_REQUESTED":
                raise reject(self.stage, contract.Failure.HOST_REJECTED)
        deadline = time.monotonic() + self.limits.settlement
        while True:
            processes = self.processes()
            if not processes:
                return
            if processes != (snapshot.process,):
                raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
            if time.monotonic() >= deadline:
                raise reject(self.stage, contract.Failure.HOST_SETTLEMENT_TIMEOUT)
            time.sleep(0.1)

    def processes(self) -> tuple[contract.ProcessIdentity, ...]:
        value, _ = self.bridge(
            InspectRequest(str(self.idea.home), str(self.idea.profile), "REJECT_UNPROVEN")
        )
        raw_processes = value.get("processes") if release.mapping(value) else None
        if not release.sequence(raw_processes):
            raise reject(self.stage, contract.Failure.HOST_RESPONSE_INVALID)
        result: list[contract.ProcessIdentity] = []
        for raw in raw_processes:
            parsed = contract.parse_process(raw)
            if isinstance(parsed, contract.Failure):
                raise reject(self.stage, parsed)
            result.append(parsed)
        return tuple(result)

    def start(self) -> None:
        value, _ = self.bridge(StartRequest(str(self.idea.home), str(self.idea.profile)))
        if not release.mapping(value) or value.get("type") != "START_REQUESTED":
            raise reject(self.stage, contract.Failure.HOST_REJECTED)

    def wait_selected(
        self,
        composition: release.AdmittedComposition,
        prior: contract.Snapshot,
        strategy: contract.Strategy,
    ) -> contract.Loaded:
        deadline = time.monotonic() + self.limits.activation
        while True:
            try:
                current, _ = self.inspect(False)
                if contract.matches_selected(
                    current, composition.identity, composition.contract
                ) and isinstance(current, contract.Loaded):
                    changed_host = isinstance(prior, contract.Loaded) and (
                        current.identity.buildIdentity != prior.identity.buildIdentity
                        or current.contract != prior.contract
                    )
                    if (
                        isinstance(prior, contract.Loaded)
                        and (changed_host or strategy == contract.Strategy.NORMAL_RESTART)
                        and current.identity.hostLifetime == prior.identity.hostLifetime
                    ):
                        raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
                    if (
                        strategy == contract.Strategy.NORMAL_RESTART
                        and isinstance(prior, (contract.Loaded, contract.Legacy))
                        and current.process == prior.process
                    ):
                        raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
                    if (
                        strategy == contract.Strategy.HOT_RELOAD
                        and isinstance(prior, contract.Loaded)
                        and current.process != prior.process
                    ):
                        raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
                    return current
            except StageRejected as failure:
                if (
                    failure.failure.reason != contract.Failure.HOST_REJECTED
                    or failure.failure.adapterCode
                    not in ("HOST_UNAVAILABLE", "PLUGIN_UNAVAILABLE", "EXCHANGE_DEADLINE_EXCEEDED")
                ):
                    raise
            if time.monotonic() >= deadline:
                raise reject(self.stage, contract.Failure.HOST_ACTIVATION_TIMEOUT)
            time.sleep(0.1)

    def control_environment(self, composition: release.AdmittedComposition) -> dict[str, str]:
        return {
            "KAST_INSTALL_CONTROL_ROOT": str(composition.control_root),
            "KAST_INSTALL_CONTROL_ARCHIVE": str(composition.control_archive),
            "KAST_INSTALL_CONTROL_SHA256": composition.control_digest,
            "KAST_INSTALL_VERSION": composition.identity.version,
            "KAST_INSTALL_IDEA_HOME": str(self.idea.home),
            "KAST_INSTALL_IDEA_PLUGIN_ROOT": str(self.idea.profile / "plugins"),
            "KAST_INSTALL_JAVA_HOME": str(self.idea.java),
            "KAST_BIN_DIR": str(self.home / ".local/bin"),
            "CODEX_HOME": str(self.home / ".codex"),
            "KAST_INSTALL_PROFILE": "persistent",
            "KAST_INSTALL_MODE": "apply",
            "KAST_INSTALL_FORCE": "0",
            "KAST_INSTALL_CONTROL_ONLY": "0",
        }

    def resume_unchanged(
        self,
        loaded: contract.Loaded,
        prior: contract.Snapshot,
        strategy: contract.Strategy,
        transaction: str,
    ) -> None:
        if (
            strategy != contract.Strategy.HOT_RELOAD
            or not isinstance(prior, contract.Loaded)
            or loaded.identity.hostLifetime != prior.identity.hostLifetime
        ):
            return
        if loaded != prior:
            raise reject(self.stage, contract.Failure.CONCURRENT_CHANGE)
        _ = self.host_action(loaded, transaction, "RESUME")

    def control_action(
        self,
        composition: release.AdmittedComposition,
        transaction: str,
        action: Literal["prepare", "commit", "abort"],
    ) -> None:
        raw = self.child(
            [
                str(self.control / "share/kast/libexec/kast-service"),
                "managed-control",
                action,
                transaction,
            ],
            self.control_environment(composition),
            ChildProtocol.MANAGEMENT_DOCUMENT,
        )
        document = release.document(raw)
        if release.mapping(document) and document.get("type") in ("REJECTED", "RECOVERY_REQUIRED"):
            code = document.get("reason", document.get("failure"))
            raise reject(
                self.stage,
                contract.Failure.CONTROL_REJECTED,
                code if isinstance(code, str) else None,
            )
        expected = {"prepare": "PREPARED", "commit": "COMMITTED", "abort": "ABORTED"}[action]
        if (
            not release.mapping(document)
            or document.get("type") != expected
            or document.get("transaction") != transaction
        ):
            raise reject(self.stage, contract.Failure.CONTROL_REJECTED)


def saved_file(path: Path, preimage: Path) -> journal_types.ManagedFilePreimage:
    if not os.path.lexists(path):
        return journal_types.ManagedFileAbsent(str(path), "ABSENT")
    data = read_record(path, 67_108_864)
    _ = preimage.write_bytes(data)
    preimage.chmod(0o600)
    return journal_types.ManagedFilePresent(
        str(path),
        str(preimage),
        hashlib.sha256(data).hexdigest(),
        stat.S_IMODE(path.stat().st_mode),
        "PRESENT",
    )


def stored_file(path: Path, data: bytes) -> journal_types.ManagedFilePresent:
    _ = path.write_bytes(data)
    path.chmod(0o600)
    return journal_types.ManagedFilePresent(
        str(path), str(path), hashlib.sha256(data).hexdigest(), 0o600, "PRESENT"
    )


def file_matches(path: Path, digest: str, mode: int) -> bool:
    return (
        os.path.lexists(path)
        and hashlib.sha256(read_record(path, 67_108_864)).hexdigest() == digest
        and stat.S_IMODE(path.stat().st_mode) == mode
    )


def exchange_files(first: Path, second: Path) -> None:
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == "darwin":
        rename = library.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(first), os.fsencode(second), 2)
    else:
        rename = library.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        result = rename(-100, os.fsencode(first), -100, os.fsencode(second), 2)
    if result != 0:
        raise OSError(ctypes.get_errno(), "owned file exchange failed")


def replace_file(
    path: Path, data: bytes, mode: int, expected: journal_types.ManagedFilePreimage, token: str
) -> None:
    """Keep a displaced concurrent file until its captured preimage proves ownership."""
    temporary = path.with_name(".kast-managed-" + token)
    selected_digest = hashlib.sha256(data).hexdigest()
    if os.path.lexists(temporary):
        if (
            isinstance(expected, journal_types.ManagedFilePresent)
            and file_matches(temporary, expected.sha256, expected.mode)
            and file_matches(path, selected_digest, mode)
        ):
            temporary.unlink()
            RECOVERY.sync(path.parent)
            return
        if not file_matches(temporary, selected_digest, mode):
            raise reject(contract.Stage.RECOVERY, contract.Failure.CONCURRENT_CHANGE)
    else:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
        with os.fdopen(descriptor, "wb") as output:
            _ = output.write(data)
            output.flush()
            os.fchmod(output.fileno(), mode)
            os.fsync(output.fileno())
    selected_identity = journal_types.Identity.observe(temporary)
    try:
        if not unchanged_preimage(expected):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE)
        if isinstance(expected, journal_types.ManagedFileAbsent):
            os.link(temporary, path, follow_symlinks=False)
        else:
            exchange_files(temporary, path)
            if not file_matches(temporary, expected.sha256, expected.mode):
                if journal_types.Identity.observe(path) == selected_identity and file_matches(
                    path, selected_digest, mode
                ):
                    exchange_files(temporary, path)
                raise reject(contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE)
        if journal_types.Identity.observe(path) != selected_identity or not file_matches(
            path, selected_digest, mode
        ):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE)
        RECOVERY.sync(path.parent)
        temporary.unlink()
        RECOVERY.sync(path.parent)
    except BaseException:
        if (
            os.path.lexists(temporary)
            and journal_types.Identity.observe(temporary) == selected_identity
            and file_matches(temporary, selected_digest, mode)
        ):
            temporary.unlink()
        raise


def reload_compatible(idea: SelectedIdea, composition: release.AdmittedComposition) -> bool:
    current = reload_hosted.read_installed(idea.profile / "plugins/kast-ide-hosted/lib")
    candidate = reload_hosted.read_archive(composition.host_archive)
    return (
        isinstance(current, reload_hosted.Bundle)
        and isinstance(candidate, reload_hosted.Bundle)
        and isinstance(reload_hosted.plan(current, candidate), reload_hosted.Replacement)
    )


def binding_from_composition(composition: release.AdmittedComposition) -> SelectionBinding:
    return SelectionBinding(composition.identity, composition.policy)


def unchanged_preimage(preimage: journal_types.ManagedFilePreimage) -> bool:
    path = Path(preimage.destination)
    if isinstance(preimage, journal_types.ManagedFileAbsent):
        return not os.path.lexists(path)
    return (
        os.path.lexists(path)
        and hashlib.sha256(read_record(path, 67_108_864)).hexdigest() == preimage.sha256
        and stat.S_IMODE(path.stat().st_mode) == preimage.mode
    )


def rebind_control(adapter: NativeComposition, record: journal_types.Replacement) -> None:
    reservation, _ = journal_types.managed_parts(record)
    candidates = (
        Path(reservation.staged),
        adapter.root / "installation",
        adapter.root / "recovery/replacement/rejected-control",
    )
    matching = [
        path
        for path in candidates
        if path.is_dir()
        and not path.is_symlink()
        and journal_types.Identity.observe(path) == record.installationIdentity
    ]
    if len(matching) != 1:
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    RECOVERY.managed_payload(
        matching[0], adapter.root / "installation", record.installationIdentity, reservation
    )
    adapter.control = matching[0]


def acquire_host_lease(
    adapter: NativeComposition, record: journal_types.Replacement
) -> tuple[journal_types.Replacement, journal_types.ManagedComposition]:
    _, core = journal_types.managed_parts(record)
    if adapter.host_lease is not None:
        if adapter.host_lease.record != core.host:
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        return record, core
    lock = core.host.lock
    if lock is None:
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    retained = adapter.idea.profile / ".kast-plugin-recovery"
    if isinstance(lock, journal_types.ManagedHostStagedLock) and lock.retainedIdentity is None:
        if core.host.retainedRootIdentity is None:
            created = next(
                (item for item in core.profileDirectories if item.path == str(retained)), None
            )
            if created is None:
                if os.path.lexists(retained):
                    raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
                core = replace(
                    core,
                    profileDirectories=core.profileDirectories
                    + [journal_types.ManagedProfileMissing(str(retained), "MISSING")],
                )
                record = replace(record, composition=core)
                RECOVERY.save(adapter.root / "recovery/replacement/receipt.json", record)
                retained.mkdir(mode=0o700)
                created = journal_types.ManagedProfileCreated(
                    str(retained), journal_types.Identity.observe(retained), "CREATED"
                )
                core = replace(
                    core,
                    profileDirectories=[
                        created if item.path == str(retained) else item
                        for item in core.profileDirectories
                    ],
                )
                record = replace(record, composition=core)
                RECOVERY.save(adapter.root / "recovery/replacement/receipt.json", record)
            if (
                not isinstance(created, journal_types.ManagedProfileCreated)
                or journal_types.Identity.observe(retained) != created.identity
            ):
                raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
        bound = HOST.bind_managed_lock(
            HOST.decode_managed(asdict(core.host)), HOST.Identity.observe(retained)
        )
        host = journal_host_record(RECOVERY.decode(RECOVERY.ManagedHostReplacement, asdict(bound)))
        core = replace(core, host=host)
        record = replace(record, composition=core)
        RECOVERY.save(adapter.root / "recovery/replacement/receipt.json", record)
    stack = ExitStack()
    try:
        _ = stack.enter_context(HOST.managed_lock(HOST.decode_managed(asdict(core.host))))
    except BaseException:
        stack.close()
        raise
    adapter.host_lease = HostLease(stack, core.host)
    return record, core


def close_host_lease(adapter: NativeComposition) -> None:
    if adapter.host_lease is not None:
        adapter.host_lease.stack.close()
        adapter.host_lease = None


def hosted_contract(control: Path) -> release.HostedContract:
    raw = release.document(read_record(control / "share/kast/ide-host.json"))
    admitted = release.parse_contract(
        raw.get("requiredHostedContract") if release.mapping(raw) else None
    )
    if isinstance(admitted, release.Failure):
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    return admitted


def admit_files(
    adapter: NativeComposition,
    identity: release.BuildIdentity,
    control_digest: str,
    host_digest: str,
    compatibility: release.HostedContract,
    expected: ManagementReceipt | None = None,
) -> contract.InstalledFiles:
    installed = adapter.root / "installation"
    admitted = LIFECYCLE.Installation.admit(str(installed))
    manifest = admitted.manifest
    if (
        manifest.get("semanticVersion") != identity.version
        or manifest.get("controlSha256") != "sha256:" + control_digest
    ):
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    source = release.document(read_record(installed / "share/kast/build-identity.json"))
    if (
        not release.mapping(source)
        or set(source) != {"version", "sourceRevision", "sourceTree"}
        or source["version"] != identity.version
        or source["sourceRevision"] != identity.buildRevision
        or source["sourceTree"] != identity.sourceTree
        or hosted_contract(installed) != compatibility
    ):
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    plugins = adapter.idea.profile / "plugins"
    active = HOST.admit_active(
        plugins,
        HOST.read_active_receipt(adapter.idea.profile / ".kast-plugin-recovery/host-install.json"),
    )
    if (
        not (plugins / "kast-ide-hosted").is_dir()
        or active.sha256 != host_digest
        or active.version != identity.version
        or HOST.inventory(plugins / "kast-ide-hosted") != active.inventory
    ):
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    receipt = parse_management(read_record(adapter.root / "management.json"))
    if (
        receipt.installationRoot != str(adapter.root)
        or receipt.installedBuild != identity
        or (expected is not None and receipt != expected)
    ):
        raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
    command = Path(receipt.executable)
    if (
        receipt.executableSha256
        != release.file_digest(installed / "share/kast/libexec/kast-management")
        or release.file_digest(command) != receipt.executableSha256
        or not os.access(command, os.X_OK)
    ):
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    return contract.InstalledFiles(
        identity, control_digest, host_digest, compatibility, adapter.idea.target
    )


def terminal_outcome(
    files: contract.InstalledFiles,
    snapshot: contract.Snapshot,
    strategy: contract.Strategy,
    notices: tuple[journal_types.ManagedNotice, ...],
) -> contract.Activated | contract.InstalledNotRunning:
    if strategy == contract.Strategy.STOPPED and isinstance(snapshot, contract.Stopped):
        return contract.InstalledNotRunning(files, snapshot, notices)
    if isinstance(snapshot, contract.Loaded) and contract.matches_selected(
        snapshot, files.identity, files.contract
    ):
        return contract.Activated(files, (snapshot,), notices)
    raise reject(contract.Stage.HOST_ACTIVATION, contract.Failure.HOST_RESPONSE_INVALID)


def already_current(
    adapter: NativeComposition,
    composition: release.AdmittedComposition,
    snapshot: contract.Snapshot,
    dry_run: bool,
) -> contract.Outcome | None:
    if not (adapter.root / "management.json").is_file():
        return None
    receipt = parse_management(read_record(adapter.root / "management.json"))
    if receipt.installedBuild != composition.identity:
        return None
    options = adapter.idea.profile / "idea.vmoptions"
    if not options.is_file() or options.is_symlink():
        return None
    previous_options = read_record(options, 67_108_864)
    if previous_options != contract.profile_options(previous_options):
        return None
    files = admit_files(
        adapter,
        composition.identity,
        composition.control_digest,
        composition.host_digest,
        composition.contract,
    )
    if not isinstance(snapshot, contract.Stopped) and not contract.matches_selected(
        snapshot, composition.identity, composition.contract
    ):
        return None
    strategy = (
        contract.Strategy.STOPPED
        if isinstance(snapshot, contract.Stopped)
        else contract.Strategy.HOT_RELOAD
    )
    if dry_run:
        return contract.Planned(composition.identity, strategy)
    if receipt.updatePolicy != composition.policy:
        binding = adapter.temporary / "current-selection.json"
        _ = binding.write_bytes(encoded(binding_from_composition(composition)))
        binding.chmod(0o600)
        _ = adapter.child(
            [str(adapter.native), "--internal-install", "managed-publish"],
            {"KAST_MANAGEMENT_SELECTION": str(binding)},
        )
        expected = replace(
            receipt,
            updatePolicy=composition.policy,
            channel="STABLE" if composition.identity.mainRevision is None else "DEVELOPER",
        )
        files = admit_files(
            adapter,
            composition.identity,
            composition.control_digest,
            composition.host_digest,
            composition.contract,
            expected,
        )
    current, _ = adapter.inspect(False)
    if current != snapshot:
        raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
    return terminal_outcome(files, current, strategy, ())


def replace_host(
    adapter: NativeComposition, strategy: contract.Strategy, host_record: object
) -> None:
    adapter.enter(contract.Stage.HOST_REPLACEMENT)
    if strategy != contract.Strategy.HOT_RELOAD:
        current, _ = adapter.inspect(False)
        if not isinstance(current, contract.Stopped):
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
    if strategy == contract.Strategy.HOT_RELOAD:
        HOST.commit_managed_reload(host_record)
    else:
        HOST.commit_managed(host_record)


def install_composition(
    adapter: NativeComposition,
    composition: release.AdmittedComposition,
    restart: bool,
    dry_run: bool,
) -> contract.Outcome:
    prepared_host: object | None = None
    prepared_files: list[journal_types.ManagedFilePresent] = []
    transaction: str | None = None
    try:
        prior, raw_prior = adapter.inspect(not dry_run)
        if len(raw_prior) > 262_144:
            return contract.Rejected(adapter.stage, contract.Failure.HOST_RESPONSE_INVALID)
        if (adapter.root / "management.json").is_file():
            if dry_run:
                existing = already_current(adapter, composition, prior, True)
            else:
                with management_lock(adapter.root):
                    fresh = adapter.readmit(prior)
                    existing = already_current(adapter, composition, fresh, False)
            if existing is not None:
                return existing
        strategy = contract.choose_strategy(
            prior, reload_compatible(adapter.idea, composition), restart
        )
        if isinstance(strategy, contract.Rejected):
            return strategy
        destination = Path(
            adapter.child([str(adapter.native), "--internal-install", "preflight"]).decode().strip()
        )
        if dry_run:
            return contract.Planned(composition.identity, strategy)
        with management_lock(adapter.root):
            _ = adapter.readmit(prior)
            transaction = str(uuid.uuid4())
            adapter.enter(contract.Stage.JOURNAL)
            adapter.control_action(composition, transaction, "prepare")
            journal = adapter.root / "recovery/replacement"
            record = journal_types_record(
                RECOVERY.load(journal / "receipt.json", RECOVERY.Replacement)
            )
            host_request = HOST.HostInstallRequest(
                HOST.HostedPluginPayload(
                    composition.host_archive,
                    composition.host_digest,
                    composition.identity.version,
                    adapter.idea.build,
                ),
                adapter.idea.profile / "plugins",
            )
            host_record = HOST.prepare_managed(host_request, journal)
            prepared_host = host_record
            selected = stored_file(
                journal / "selection.json", encoded(binding_from_composition(composition))
            )
            prepared_files.append(selected)
            management = saved_file(
                adapter.root / "management.json", journal / "management-preimage"
            )
            if isinstance(management, journal_types.ManagedFilePresent):
                prepared_files.append(management)
            executable = saved_file(destination, journal / "command-preimage")
            if isinstance(executable, journal_types.ManagedFilePresent):
                prepared_files.append(executable)
            options = saved_file(
                adapter.idea.profile / "idea.vmoptions", journal / "options-preimage"
            )
            if isinstance(options, journal_types.ManagedFilePresent):
                prepared_files.append(options)
            runtime = stored_file(journal / "runtime-preimage", raw_prior)
            prepared_files.append(runtime)
            missing: list[journal_types.ManagedProfileDirectory] = []
            directory = adapter.idea.profile
            while not directory.exists():
                missing.append(journal_types.ManagedProfileMissing(str(directory), "MISSING"))
                directory = directory.parent
            directories = list(reversed(missing))
            old_digest = None
            if isinstance(
                record.previous, (journal_types.PhysicalPrevious, journal_types.LegacyPrevious)
            ):
                old_digest = release.integration_digest(Path(record.previous.installation))
            notices = (
                [journal_types.ManagedNotice.HARNESS_REFRESH_REQUIRED]
                if old_digest is not None and old_digest != composition.integration_digest
                else []
            )
            typed_host = journal_host_record(
                RECOVERY.decode(RECOVERY.ManagedHostReplacement, asdict(host_record))
            )
            core = journal_types.ManagedComposition(
                journal_types.ManagedPhase.PREPARED,
                journal_types.ManagedStrategy(strategy.value),
                str(adapter.idea.home),
                str(adapter.idea.profile),
                selected,
                management,
                executable,
                options,
                typed_host,
                runtime,
                directories,
                journal_types.ManagedBinding(composition.identity, composition.policy),
                notices,
                raw_prior.decode("utf-8"),
            )
            record = replace(record, composition=core)
            RECOVERY.save(journal / "receipt.json", record)
            try:
                if not all(
                    unchanged_preimage(preimage) for preimage in (management, executable, options)
                ):
                    raise reject(contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE)
                _ = adapter.readmit(prior)
                adapter.enter(contract.Stage.FENCE)
                record, core = advance(record, core, journal_types.ManagedPhase.FENCED)
                RECOVERY.save(journal / "receipt.json", record)
                if isinstance(record.previous, RECOVERY.NoPrevious):
                    ensure_fence(adapter)
                else:
                    _ = adapter.child([str(adapter.native), "--internal-install", "managed-retire"])
                adapter.enter(contract.Stage.HOST_SETTLEMENT)
                if strategy == contract.Strategy.STOPPED:
                    current, _ = adapter.inspect(False)
                    if not isinstance(current, contract.Stopped):
                        raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
                elif strategy == contract.Strategy.HOT_RELOAD and isinstance(
                    prior, contract.Loaded
                ):
                    adapter.settle(prior, transaction)
                elif isinstance(prior, (contract.Loaded, contract.Legacy)):
                    adapter.stop(prior, transaction)
                else:
                    raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
                record, core = advance(record, core, journal_types.ManagedPhase.SETTLED)
                RECOVERY.save(journal / "receipt.json", record)
                adapter.enter(contract.Stage.PROFILE)
                for index, item in enumerate(core.profileDirectories):
                    path = Path(item.path)
                    path.mkdir(mode=0o700)
                    changed = list(core.profileDirectories)
                    changed[index] = journal_types.ManagedProfileCreated(
                        item.path, journal_types.Identity.observe(path), "CREATED"
                    )
                    core = replace(core, profileDirectories=changed)
                    record = replace(record, composition=core)
                    RECOVERY.save(journal / "receipt.json", record)
                previous_options = (
                    read_record(Path(options.preimage))
                    if isinstance(options, journal_types.ManagedFilePresent)
                    else b""
                )
                replace_file(
                    adapter.idea.profile / "idea.vmoptions",
                    contract.profile_options(previous_options),
                    options.mode
                    if isinstance(options, journal_types.ManagedFilePresent)
                    else 0o600,
                    options,
                    transaction + "-options",
                )
                adapter.enter(contract.Stage.CONTROL_REPLACEMENT)
                record, core = acquire_host_lease(adapter, record)
                host_record = HOST.decode_managed(asdict(core.host))
                HOST.admit_managed_prepared(host_record)
                adapter.control_action(composition, transaction, "commit")
                record = journal_types_record(
                    RECOVERY.load(journal / "receipt.json", RECOVERY.Replacement)
                )
                _, core = journal_types.managed_parts(record)
                rebind_control(adapter, record)
                record, core = advance(record, core, journal_types.ManagedPhase.CONTROL_COMMITTED)
                RECOVERY.save(journal / "receipt.json", record)
                replace_host(adapter, strategy, host_record)
                record, core = advance(record, core, journal_types.ManagedPhase.HOST_COMMITTED)
                RECOVERY.save(journal / "receipt.json", record)
                adapter.enter(contract.Stage.HOST_ACTIVATION)
                if strategy == contract.Strategy.NORMAL_RESTART:
                    adapter.start()
                loaded = None
                if strategy == contract.Strategy.STOPPED:
                    current, _ = adapter.inspect(False)
                    if not isinstance(current, contract.Stopped):
                        raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
                else:
                    loaded = adapter.wait_selected(composition, prior, strategy)
                    adapter.resume_unchanged(loaded, prior, strategy, transaction)
                adapter.enter(contract.Stage.RECEIPTS)
                if not all(unchanged_preimage(preimage) for preimage in (management, executable)):
                    raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
                _ = adapter.child(
                    [str(adapter.native), "--internal-install", "managed-publish"],
                    {"KAST_MANAGEMENT_SELECTION": str(journal / "selection.json")},
                )
                files = admit_files(
                    adapter,
                    composition.identity,
                    composition.control_digest,
                    composition.host_digest,
                    composition.contract,
                    selected_management(adapter, core),
                )
                final_runtime, _ = adapter.inspect(False)
                if final_runtime != (loaded if loaded is not None else prior):
                    raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
                record, core = advance(record, core, journal_types.ManagedPhase.ACTIVATION_PROVEN)
                RECOVERY.save(journal / "receipt.json", record)
                outcome = terminal_outcome(files, final_runtime, strategy, tuple(core.notices))
                finish_transaction(adapter, record, host_record)
                return outcome
            except (
                StageRejected,
                OSError,
                ValueError,
                HOST.Rejected,
                RECOVERY.Rejected,
                LIFECYCLE.Rejected,
                KeyboardInterrupt,
            ) as failure:
                problem = stage_failure(adapter, failure)
                if core.phase in (
                    journal_types.ManagedPhase.ACTIVATION_PROVEN,
                    journal_types.ManagedPhase.RESTORED,
                ):
                    return contract.RecoveryRequired(
                        transaction,
                        problem,
                        contract.Rejected(
                            contract.Stage.FINALIZATION, contract.Failure.RECOVERY_UNPROVEN
                        ),
                    )
                return restore_transaction(adapter, record, problem)
    except StageRejected as failure:
        return failure.failure
    except (
        OSError,
        ValueError,
        HOST.Rejected,
        RECOVERY.Rejected,
        LIFECYCLE.Rejected,
        KeyboardInterrupt,
    ) as failure:
        problem = stage_failure(adapter, failure)
        pending = adapter.root / "recovery/replacement/receipt.json"
        if os.path.lexists(pending):
            try:
                current = journal_types_record(RECOVERY.load(pending, RECOVERY.Replacement))
                if current.composition is not None:
                    return restore_transaction(adapter, current, problem)
                if transaction is not None:
                    abort_preparation(
                        adapter, composition, transaction, prepared_host, prepared_files
                    )
                    return problem
            except (
                StageRejected,
                OSError,
                ValueError,
                HOST.Rejected,
                RECOVERY.Rejected,
                KeyboardInterrupt,
            ):
                pass
            return contract.RecoveryRequired(
                "prepared",
                problem,
                contract.Rejected(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN),
            )
        return problem
    finally:
        close_host_lease(adapter)


def abort_preparation(
    adapter: NativeComposition,
    composition: release.AdmittedComposition,
    transaction: str,
    host_record: object | None,
    preimages: list[journal_types.ManagedFilePresent],
) -> None:
    directory = adapter.root / "recovery/replacement"
    names = {"receipt.json"} | {Path(item.preimage).name for item in preimages}
    if host_record is not None:
        names |= {"host-candidate", "host-prior", "host-receipt", "host-lock"}
    if {path.name for path in directory.iterdir()} - names:
        raise reject(contract.Stage.RECOVERY, contract.Failure.OWNERSHIP_UNPROVEN)
    for item in preimages:
        if (
            Path(item.preimage).parent != directory
            or hashlib.sha256(read_record(Path(item.preimage), 67_108_864)).hexdigest()
            != item.sha256
        ):
            raise reject(contract.Stage.RECOVERY, contract.Failure.OWNERSHIP_UNPROVEN)
    if host_record is not None:
        HOST.rollback_managed(host_record)
        HOST.seal_managed(host_record, HOST.ManagedHostTerminalPhase.RESTORED)
        HOST.finish_managed_lock(host_record, HOST.ManagedHostTerminalPhase.RESTORED)
    for item in preimages:
        path = Path(item.preimage)
        if hashlib.sha256(read_record(path, 67_108_864)).hexdigest() != item.sha256:
            raise reject(contract.Stage.RECOVERY, contract.Failure.OWNERSHIP_UNPROVEN)
        path.unlink()
    adapter.control_action(composition, transaction, "abort")


def journal_types_record(value: object) -> journal_types.Replacement:
    return journal_types.admit_replacement(value)


def stage_failure(adapter: NativeComposition, failure: BaseException) -> contract.Rejected:
    if isinstance(failure, StageRejected):
        return failure.failure
    if isinstance(failure, KeyboardInterrupt):
        if adapter.interrupt.exit_code is None:
            adapter.interrupt.exit_code = 130
        return contract.Rejected(adapter.stage, contract.Failure.CANCELLED)
    code = getattr(failure, "failure", None)
    return contract.Rejected(
        adapter.stage,
        contract.Failure.FILESYSTEM_REJECTED,
        str(code.value) if isinstance(code, Enum) else str(code) if code is not None else None,
    )


def advance(
    record: journal_types.Replacement,
    core: journal_types.ManagedComposition,
    phase: journal_types.ManagedPhase,
) -> tuple[journal_types.Replacement, journal_types.ManagedComposition]:
    if record.composition != core:
        raise reject(contract.Stage.JOURNAL, contract.Failure.CONCURRENT_CHANGE)
    changed = replace(core, phase=phase)
    return replace(record, composition=changed), changed


def ensure_fence(adapter: NativeComposition) -> None:
    path = adapter.root / "shutdown.json"
    if os.path.lexists(path):
        _ = RECOVERY.managed_shutdown_fence(adapter.root / "installation")
        return
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        _ = stream.write(encoded(ShutdownRequest(str(adapter.root / "installation"))))
        stream.flush()
        os.fsync(stream.fileno())
    RECOVERY.sync(adapter.root)


def journal_host_record(value: object) -> journal_types.ManagedHostReplacement:
    if not isinstance(value, journal_types.ManagedHostReplacement):
        raise reject(contract.Stage.JOURNAL, contract.Failure.OWNERSHIP_UNPROVEN)
    return value


def finish_transaction(
    adapter: NativeComposition, record: journal_types.Replacement, host_record: object
) -> None:
    adapter.enter(contract.Stage.FINALIZATION)
    _, core = journal_types.managed_parts(record)
    phase = (
        HOST.ManagedHostTerminalPhase.ACTIVATION_PROVEN
        if core.phase == "ACTIVATION_PROVEN"
        else HOST.ManagedHostTerminalPhase.RESTORED
    )
    HOST.seal_managed(host_record, phase)
    RECOVERY.seal_managed_control(adapter.root / "installation", record)
    close_host_lease(adapter)
    HOST.finish_managed_lock(host_record, phase)
    if core.phase == journal_types.ManagedPhase.RESTORED:
        remove_profile_directories(adapter, core)
    RECOVERY.finalize_managed_journal(adapter.root / "installation", record)


def restore_transaction(
    adapter: NativeComposition, record: journal_types.Replacement, failure: contract.Rejected
) -> contract.RolledBack | contract.RecoveryRequired:
    adapter.enter(contract.Stage.RECOVERY)
    journal = adapter.root / "recovery/replacement"
    try:
        current_record = journal_types_record(
            RECOVERY.load(journal / "receipt.json", RECOVERY.Replacement)
        )
        reservation, core = journal_types.managed_parts(current_record)
        original_reservation, original_core = journal_types.managed_parts(record)
        if (
            reservation != original_reservation
            or current_record.installationIdentity != record.installationIdentity
            or current_record.previous != record.previous
            or replace(core, phase=original_core.phase) != original_core
        ):
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        if core.phase in (
            journal_types.ManagedPhase.ACTIVATION_PROVEN,
            journal_types.ManagedPhase.RESTORED,
        ):
            return contract.RecoveryRequired(
                reservation.transaction,
                failure,
                contract.Rejected(contract.Stage.FINALIZATION, contract.Failure.RECOVERY_UNPROVEN),
            )
        prepared_only = core.phase == journal_types.ManagedPhase.PREPARED
        if not prepared_only:
            ensure_fence(adapter)
        prior = prior_runtime(core)
        host_record = HOST.decode_managed(asdict(core.host))
        current_record, core = advance(current_record, core, journal_types.ManagedPhase.RESTORING)
        RECOVERY.save(journal / "receipt.json", current_record)
        current, _ = adapter.inspect()
        if (
            not isinstance(current, contract.Stopped)
            and not restored_runtime(current, prior)
            and not (
                isinstance(current, contract.Loaded)
                and core.binding is not None
                and current.identity.buildIdentity
                == contract.RuntimeBuild(
                    core.binding.identity.version,
                    core.binding.identity.buildRevision,
                    core.binding.identity.sourceTree,
                )
            )
        ):
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        if prepared_only and current != prior:
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        if isinstance(prior, contract.Stopped) and not isinstance(current, contract.Stopped):
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        current_record, core = create_profile_directories(adapter, current_record)
        current_record, core = acquire_host_lease(adapter, current_record)
        host_record = HOST.decode_managed(asdict(core.host))
        if (
            not prepared_only
            and core.strategy == "NORMAL_RESTART"
            and not isinstance(current, contract.Stopped)
        ):
            adapter.stop(current, reservation.transaction)
        elif (
            not prepared_only
            and core.strategy == "HOT_RELOAD"
            and isinstance(current, contract.Loaded)
        ):
            adapter.settle(current, reservation.transaction)
        if core.strategy == "HOT_RELOAD":
            HOST.rollback_managed_reload(host_record)
        else:
            HOST.rollback_managed(host_record)
        RECOVERY.rollback_managed_control(adapter.root / "installation", current_record)
        rebind_control(adapter, current_record)
        for preimage in (core.management, core.publicExecutable, core.profileOptions):
            restore_file(preimage, adapter, current_record)
        if isinstance(prior, contract.Stopped):
            actual, _ = adapter.inspect(False)
            if not isinstance(actual, contract.Stopped):
                raise reject(adapter.stage, contract.Failure.RECOVERY_UNPROVEN)
        else:
            if not prepared_only and core.strategy == "NORMAL_RESTART":
                adapter.start()
            deadline = time.monotonic() + adapter.limits.activation
            while True:
                actual, _ = adapter.inspect()
                if restored_runtime(actual, prior) and (
                    core.strategy != "HOT_RELOAD"
                    or (
                        isinstance(actual, (contract.Loaded, contract.Legacy))
                        and actual.process == prior.process
                    )
                ):
                    if (
                        not prepared_only
                        and isinstance(actual, contract.Loaded)
                        and isinstance(prior, contract.Loaded)
                        and actual.identity.hostLifetime == prior.identity.hostLifetime
                    ):
                        _ = adapter.host_action(actual, reservation.transaction, "RESUME")
                    break
                if time.monotonic() >= deadline:
                    raise reject(adapter.stage, contract.Failure.RECOVERY_UNPROVEN)
                time.sleep(0.1)
        if not all(
            unchanged_preimage(item)
            for item in (core.management, core.publicExecutable, core.profileOptions)
        ):
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        current_record, core = advance(current_record, core, journal_types.ManagedPhase.RESTORED)
        RECOVERY.save(journal / "receipt.json", current_record)
        finish_transaction(adapter, current_record, host_record)
        return contract.RolledBack(
            reservation.transaction,
            failure,
            contract.RestoredComposition(current_record.previous, core.host.previous, actual),
        )
    except (
        StageRejected,
        OSError,
        ValueError,
        HOST.Rejected,
        RECOVERY.Rejected,
        LIFECYCLE.Rejected,
        KeyboardInterrupt,
    ) as recovery:
        problem = stage_failure(adapter, recovery)
        return contract.RecoveryRequired(
            record.reservation.transaction if record.reservation else "unavailable",
            failure,
            problem,
        )
    finally:
        close_host_lease(adapter)


def create_profile_directories(
    adapter: NativeComposition, record: journal_types.Replacement
) -> tuple[journal_types.Replacement, journal_types.ManagedComposition]:
    _, core = journal_types.managed_parts(record)
    for index, item in enumerate(core.profileDirectories):
        path = Path(item.path)
        if isinstance(item, journal_types.ManagedProfileCreated):
            if not path.exists() or journal_types.Identity.observe(path) != item.identity:
                raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
            continue
        if os.path.lexists(path):
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        path.mkdir(mode=0o700)
        changed = list(core.profileDirectories)
        changed[index] = journal_types.ManagedProfileCreated(
            item.path, journal_types.Identity.observe(path), "CREATED"
        )
        core = replace(core, profileDirectories=changed)
        record = replace(record, composition=core)
        RECOVERY.save(adapter.root / "recovery/replacement/receipt.json", record)
    return record, core


def prior_runtime(core: journal_types.ManagedComposition) -> contract.Snapshot:
    preimage = core.priorRuntime
    if not isinstance(preimage, journal_types.ManagedFilePresent):
        raise reject(contract.Stage.RECOVERY, contract.Failure.OWNERSHIP_UNPROVEN)
    raw = (
        core.priorRuntimeJson.encode("utf-8")
        if core.priorRuntimeJson is not None
        else read_record(Path(preimage.preimage), 262_144)
    )
    if hashlib.sha256(raw).hexdigest() != preimage.sha256:
        raise reject(contract.Stage.RECOVERY, contract.Failure.OWNERSHIP_UNPROVEN)
    prior = contract.parse_inspection(release.document(raw), core.ideaHome, core.profile)
    if isinstance(prior, contract.Rejected):
        raise StageRejected(prior)
    return prior


def remove_profile_directories(
    adapter: NativeComposition, core: journal_types.ManagedComposition
) -> None:
    for item in reversed(core.profileDirectories):
        path = Path(item.path)
        if isinstance(item, journal_types.ManagedProfileMissing):
            if os.path.lexists(path):
                raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
        elif os.path.lexists(path):
            if journal_types.Identity.observe(path) != item.identity or path.is_symlink():
                raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
            path.rmdir()


def restored_runtime(current: contract.Snapshot, prior: contract.Snapshot) -> bool:
    if isinstance(current, contract.Stopped) or isinstance(prior, contract.Stopped):
        return (
            isinstance(current, contract.Stopped)
            and isinstance(prior, contract.Stopped)
            and current.target == prior.target
        )
    if isinstance(current, contract.Loaded) and isinstance(prior, contract.Loaded):
        return (
            current.identity.ideaHome == prior.identity.ideaHome
            and current.identity.profile == prior.identity.profile
            and current.identity.buildIdentity == prior.identity.buildIdentity
            and current.contract == prior.contract
        )
    return (
        isinstance(current, contract.Legacy)
        and isinstance(prior, contract.Legacy)
        and current.ideaHome == prior.ideaHome
        and current.profile == prior.profile
        and prior.version is not None
        and prior.contractDigest is not None
        and current.version == prior.version
        and current.contractDigest == prior.contractDigest
    )


def selected_file(
    preimage: journal_types.ManagedFilePreimage,
    adapter: NativeComposition,
    core: journal_types.ManagedComposition,
    prior: bytes,
) -> tuple[bytes, int, str]:
    path = Path(preimage.destination)
    if path == adapter.idea.profile / "idea.vmoptions":
        return (
            contract.profile_options(prior),
            preimage.mode if isinstance(preimage, journal_types.ManagedFilePresent) else 0o600,
            "options",
        )
    if path == adapter.root / "management.json":
        current = read_record(path)
        if parse_management(current) != selected_management(adapter, core):
            raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
        return current, 0o600, "management"
    if path == Path(core.publicExecutable.destination):
        return (
            read_record(adapter.control / "share/kast/libexec/kast-management", 67_108_864),
            0o755,
            "command",
        )
    raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)


def restore_file(
    preimage: journal_types.ManagedFilePreimage,
    adapter: NativeComposition,
    record: journal_types.Replacement,
) -> None:
    reservation, core = journal_types.managed_parts(record)
    path = Path(preimage.destination)
    prior = (
        read_record(Path(preimage.preimage), 67_108_864)
        if isinstance(preimage, journal_types.ManagedFilePresent)
        else b""
    )
    if (
        isinstance(preimage, journal_types.ManagedFilePresent)
        and hashlib.sha256(prior).hexdigest() != preimage.sha256
    ):
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    if unchanged_preimage(preimage):
        return
    selected, selected_mode, role = selected_file(preimage, adapter, core, prior)
    selected_digest = hashlib.sha256(selected).hexdigest()
    if not file_matches(path, selected_digest, selected_mode):
        raise reject(adapter.stage, contract.Failure.OWNERSHIP_UNPROVEN)
    expected = journal_types.ManagedFilePresent(
        str(path), str(path), selected_digest, selected_mode, "PRESENT"
    )
    if isinstance(preimage, journal_types.ManagedFilePresent):
        replace_file(path, prior, preimage.mode, expected, reservation.transaction + "-" + role)
    else:
        removed = path.with_name(
            ".kast-managed-" + reservation.transaction + "-" + role + "-removed"
        )
        if os.path.lexists(removed):
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        # Rename keeps the actual displaced bytes until ownership is proven.
        rename_file_no_replace(path, removed)
        if not file_matches(removed, selected_digest, selected_mode):
            if not os.path.lexists(path):
                rename_file_no_replace(removed, path)
            raise reject(adapter.stage, contract.Failure.CONCURRENT_CHANGE)
        removed.unlink()
        RECOVERY.sync(path.parent)


def rename_file_no_replace(first: Path, second: Path) -> None:
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == "darwin":
        rename = library.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(first), os.fsencode(second), 4)
    else:
        rename = library.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        result = rename(-100, os.fsencode(first), -100, os.fsencode(second), 1)
    if result != 0:
        raise OSError(ctypes.get_errno(), "owned file quarantine failed")


def recorded_bridge(
    root: Path, record: journal_types.Replacement, workspace: Path, source: release.ReleaseSource
) -> Path:
    reservation, core = journal_types.managed_parts(record)
    candidates = (
        Path(reservation.staged),
        root / "installation",
        root / "recovery/replacement/rejected-control",
    )
    matching = [
        candidate
        for candidate in candidates
        if candidate.is_dir()
        and not candidate.is_symlink()
        and journal_types.Identity.observe(candidate) == record.installationIdentity
    ]
    if len(matching) == 1:
        RECOVERY.managed_payload(
            matching[0], root / "installation", record.installationIdentity, reservation
        )
        return matching[0]
    if (
        matching
        or core.phase
        not in (journal_types.ManagedPhase.ACTIVATION_PROVEN, journal_types.ManagedPhase.RESTORED)
        or core.binding is None
    ):
        raise reject(contract.Stage.RECOVERY, contract.Failure.OWNERSHIP_UNPROVEN)
    identity = core.binding.identity
    name = f"kast-control-v{identity.version}-{release.PLATFORM}.tar.gz"
    archive = workspace / name
    downloaded = source.download(
        f"{release.RELEASE_URL}/{identity.releaseTag}/{name}",
        archive,
        release.MAXIMUM_PAYLOAD_BYTES,
    )
    if (
        isinstance(downloaded, release.TransferFailure)
        or downloaded.path != archive
        or downloaded.sha256 != reservation.controlSha256
        or release.file_digest(archive) != reservation.controlSha256
        or downloaded.bytes != archive.stat().st_size
    ):
        raise reject(contract.Stage.RECOVERY, contract.Failure.CONTROL_REJECTED)
    control = workspace / "control"
    if isinstance(release.extract_control(archive, control), release.Failure):
        raise reject(contract.Stage.RECOVERY, contract.Failure.CONTROL_REJECTED)
    raw = release.document(read_record(control / "share/kast/build-identity.json"))
    if (
        not release.mapping(raw)
        or set(raw) != {"version", "sourceRevision", "sourceTree"}
        or raw["version"] != identity.version
        or raw["sourceRevision"] != identity.buildRevision
        or raw["sourceTree"] != identity.sourceTree
        or read_record(control / "share/kast/managed-install-v1") != b"1\n"
    ):
        raise reject(contract.Stage.RECOVERY, contract.Failure.CONTROL_REJECTED)
    return control


def resume_terminal(
    adapter: NativeComposition, record: journal_types.Replacement
) -> contract.Outcome:
    try:
        return resume_terminal_locked(adapter, record)
    finally:
        close_host_lease(adapter)


def resume_terminal_locked(
    adapter: NativeComposition, record: journal_types.Replacement
) -> contract.Outcome:
    reservation, core = journal_types.managed_parts(record)
    binding = core.binding
    if binding is None:
        raise reject(contract.Stage.RECOVERY, contract.Failure.OWNERSHIP_UNPROVEN)
    RECOVERY.managed_terminal_control(
        adapter.root / "installation", record, adapter.root / "recovery/replacement"
    )
    if os.path.lexists(
        adapter.idea.profile / ".kast-plugin-recovery/host-install.lock"
    ) or os.path.lexists(adapter.root / "recovery/replacement/host-lock"):
        record, core = acquire_host_lease(adapter, record)
    actual, _ = adapter.inspect(core.phase == journal_types.ManagedPhase.RESTORED)
    notices = tuple(core.notices)
    if core.phase == journal_types.ManagedPhase.ACTIVATION_PROVEN:
        files = admit_files(
            adapter,
            binding.identity,
            reservation.controlSha256,
            core.host.sha256,
            hosted_contract(adapter.control),
        )
        current_management = parse_management(read_record(adapter.root / "management.json"))
        if current_management.updatePolicy != binding.policy:
            raise reject(contract.Stage.RECOVERY, contract.Failure.CONCURRENT_CHANGE)
        prior = prior_runtime(core)
        strategy = contract.Strategy(core.strategy.value)
        if isinstance(actual, contract.Loaded) and isinstance(
            prior, (contract.Loaded, contract.Legacy)
        ):
            if strategy == contract.Strategy.HOT_RELOAD and actual.process != prior.process:
                raise reject(contract.Stage.RECOVERY, contract.Failure.CONCURRENT_CHANGE)
            if strategy == contract.Strategy.NORMAL_RESTART and actual.process == prior.process:
                raise reject(contract.Stage.RECOVERY, contract.Failure.CONCURRENT_CHANGE)
            if (
                isinstance(prior, contract.Loaded)
                and (
                    actual.identity.buildIdentity != prior.identity.buildIdentity
                    or actual.contract != prior.contract
                    or strategy == contract.Strategy.NORMAL_RESTART
                )
                and actual.identity.hostLifetime == prior.identity.hostLifetime
            ):
                raise reject(contract.Stage.RECOVERY, contract.Failure.CONCURRENT_CHANGE)
        outcome: contract.Outcome = terminal_outcome(files, actual, strategy, notices)
    elif core.phase == journal_types.ManagedPhase.RESTORED:
        prior = prior_runtime(core)
        if not restored_runtime(actual, prior) or (
            core.strategy == journal_types.ManagedStrategy.HOT_RELOAD
            and isinstance(actual, (contract.Loaded, contract.Legacy))
            and isinstance(prior, (contract.Loaded, contract.Legacy))
            and actual.process != prior.process
        ):
            raise reject(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN)
        if not all(
            unchanged_preimage(preimage)
            for preimage in (core.management, core.publicExecutable, core.profileOptions)
        ):
            raise reject(contract.Stage.RECOVERY, contract.Failure.CONCURRENT_CHANGE)
        outcome = contract.RolledBack(
            reservation.transaction,
            contract.Rejected(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN),
            contract.RestoredComposition(record.previous, core.host.previous, actual),
        )
    else:
        raise reject(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN)
    finish_transaction(adapter, record, HOST.decode_managed(asdict(core.host)))
    return outcome


def recover_recorded(
    root: Path,
    home: Path,
    dry_run: bool,
    source: release.ReleaseSource | None = None,
    interrupt: InterruptState | None = None,
    runner: ChildRunner = run_child,
) -> contract.Outcome:
    failure = contract.Rejected(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN)
    transaction = "recorded"
    if dry_run:
        return contract.RecoveryRequired(transaction, failure, failure)
    try:
        with management_lock(root):
            record = journal_types_record(
                RECOVERY.load(root / "recovery/replacement/receipt.json", RECOVERY.Replacement)
            )
            reservation, core = journal_types.managed_parts(record)
            transaction = reservation.transaction
            idea = SelectedIdea(
                Path(core.ideaHome),
                Path(core.profile),
                Path(core.ideaHome) / "jbr/Contents/Home",
                "262",
                "262",
            )
            with tempfile.TemporaryDirectory(prefix="kast-managed-recovery-") as temporary:
                workspace = Path(temporary).resolve()
                control = recorded_bridge(
                    root,
                    record,
                    workspace,
                    source if source is not None else release.HttpReleaseSource(),
                )
                adapter = NativeComposition(
                    root,
                    home,
                    idea,
                    control,
                    workspace,
                    read_limits(control),
                    dict(os.environ),
                    runner,
                    interrupt,
                )
                if core.phase in (
                    journal_types.ManagedPhase.ACTIVATION_PROVEN,
                    journal_types.ManagedPhase.RESTORED,
                ):
                    return resume_terminal(adapter, record)
                return restore_transaction(adapter, record, failure)
    except (
        StageRejected,
        OSError,
        ValueError,
        HOST.Rejected,
        RECOVERY.Rejected,
        LIFECYCLE.Rejected,
        KeyboardInterrupt,
    ) as problem:
        if (
            isinstance(problem, KeyboardInterrupt)
            and interrupt is not None
            and interrupt.exit_code is None
        ):
            interrupt.exit_code = 130
        reason = problem.failure if isinstance(problem, StageRejected) else failure
        return contract.RecoveryRequired(transaction, failure, reason)


def main(
    arguments: list[str],
    source: release.ReleaseSource | None = None,
    runner: ChildRunner = run_child,
    interrupt: InterruptState | None = None,
) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    _ = group.add_argument("--channel", choices=("stable", "main"))
    _ = group.add_argument("--version")
    _ = group.add_argument("--commit")
    _ = group.add_argument("--resolved-selection", type=Path)
    _ = parser.add_argument("--idea-home")
    _ = parser.add_argument("--install-root", type=Path)
    _ = parser.add_argument("--dry-run", action="store_true")
    _ = parser.add_argument("--restart-ide", action="store_true")
    _ = parser.add_argument("--recover-only", action="store_true")
    _ = parser.add_argument("--json", action="store_true")
    args = parser.parse_args(arguments)
    state = interrupt if interrupt is not None else InterruptState()
    home = Path(os.environ["HOME"]).resolve()
    root = home / ".local/share/kast"
    outcome: contract.Outcome | SelectionRejected
    try:
        if args.install_root is not None and args.install_root != root:
            outcome = contract.Rejected(
                contract.Stage.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN
            )
        elif platform.system() != "Darwin" or platform.machine() != "arm64":
            outcome = contract.Rejected(
                contract.Stage.PREPARATION, contract.Failure.PLATFORM_UNAVAILABLE
            )
        elif (
            args.recover_only
            or os.path.lexists(root / "recovery/replacement")
            or os.path.lexists(root / "shutdown.json")
        ):
            outcome = recover_recorded(root, home, args.dry_run, source, state, runner)
        else:
            provider = source if source is not None else release.HttpReleaseSource()
            selection = (
                release.decode_selection(read_record(args.resolved_selection))
                if args.resolved_selection is not None
                else release.resolve_selection(args.channel, args.version, args.commit, provider)
            )
            if isinstance(selection, release.Rejected):
                outcome = SelectionRejected(
                    selection.reason,
                    selection.stage,
                    selection.requested,
                    selection.platform,
                    selection.selectable,
                )
            else:
                idea = select_idea(args.idea_home, home, root)
                if isinstance(idea, contract.Rejected):
                    outcome = idea
                else:
                    with tempfile.TemporaryDirectory(prefix="kast-managed-install-") as temporary:
                        workspace = Path(temporary).resolve()
                        directory = workspace / "release"
                        directory.mkdir(mode=0o700)
                        admitted = release.download_composition(
                            selection, idea.line, directory, provider
                        )
                        if isinstance(admitted, release.Rejected):
                            outcome = SelectionRejected(
                                admitted.reason,
                                admitted.stage,
                                admitted.requested,
                                admitted.platform,
                                admitted.selectable,
                            )
                        else:
                            adapter = NativeComposition(
                                root,
                                home,
                                idea,
                                admitted.control_root,
                                workspace,
                                read_limits(admitted.control_root),
                                dict(os.environ),
                                runner,
                                state,
                            )
                            outcome = install_composition(
                                adapter, admitted, args.restart_ide, args.dry_run
                            )
    except (
        StageRejected,
        OSError,
        ValueError,
        HOST.Rejected,
        RECOVERY.Rejected,
        LIFECYCLE.Rejected,
        KeyboardInterrupt,
    ) as problem:
        if isinstance(problem, KeyboardInterrupt):
            if state.exit_code is None:
                state.exit_code = 130
            outcome = contract.Rejected(contract.Stage.PREPARATION, contract.Failure.CANCELLED)
        else:
            outcome = (
                problem.failure
                if isinstance(problem, StageRejected)
                else contract.Rejected(
                    contract.Stage.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN
                )
            )
    if args.json:
        print(encoded(outcome).decode().rstrip())
    else:
        print(outcome.type)
        if isinstance(outcome, (contract.Activated, contract.InstalledNotRunning)):
            print(outcome.nextAction, file=sys.stderr)
            if "HARNESS_REFRESH_REQUIRED" in outcome.notices:
                print(
                    "HARNESS_REFRESH_REQUIRED: run kast install <harness> before a fresh session.",
                    file=sys.stderr,
                )
            print(
                "Next commands: kast versions, kast status --json, "
                "kast install codex|copilot|pi, kast install skill.",
                file=sys.stderr,
            )
        elif not isinstance(outcome, contract.Planned):
            print(encoded(outcome).decode().rstrip(), file=sys.stderr)
    return (
        state.exit_code
        if state.exit_code is not None
        else 0
        if isinstance(outcome, (contract.Activated, contract.InstalledNotRunning, contract.Planned))
        else 1
    )


if __name__ == "__main__":
    interruption = InterruptState()

    def interrupted(number: int, _frame: object) -> None:
        interruption.exit_code = 128 + number
        raise KeyboardInterrupt()

    _ = signal.signal(signal.SIGTERM, interrupted)
    _ = signal.signal(signal.SIGINT, interrupted)
    raise SystemExit(main(sys.argv[1:], interrupt=interruption))
