#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["pydantic==2.12.3"]
# [tool.uv]
# no-build = true
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Prepare a Kotlin Gradle workspace through the installed direct tool RPC."""

from __future__ import annotations

import os
import subprocess
import sys
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, Protocol

from pydantic import BaseModel, ConfigDict, TypeAdapter, ValidationError

MAXIMUM_INPUT = 65536
PREPARATION_SECONDS = 1260


class SessionStart(BaseModel):
    model_config = ConfigDict(strict=True, frozen=True)
    hook_event_name: Literal["SessionStart"]
    source: Literal["startup", "resume", "fork", "clear", "compact"]
    cwd: str


class HealthRequest(BaseModel):
    verbose: Literal[True] = True


class IndexedWorkspace(BaseModel):
    model_config = ConfigDict(strict=True, frozen=True)
    workspaceBinding: str
    readiness: Literal["READY"]
    hostState: Literal["INDEXED"]
    contentView: Literal["SAVED_PSI_COMMITTED"]


class HealthDocument(BaseModel):
    status: Literal["complete"]
    data: IndexedWorkspace


class HealthReply(BaseModel):
    type: Literal["complete"]
    document: HealthDocument


class RpcFailure(StrEnum):
    INSTALLATION_STOPPED = "INSTALLATION_STOPPED"
    OBSERVATION_UNAVAILABLE = "OBSERVATION_UNAVAILABLE"
    CATALOG_UNAVAILABLE = "CATALOG_UNAVAILABLE"
    INVALID_COMMAND = "INVALID_COMMAND"
    REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE"
    UNKNOWN_TOOL = "UNKNOWN_TOOL"
    INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    OUT_OF_SCOPE = "OUT_OF_SCOPE"
    INVOCATION_FAILED = "INVOCATION_FAILED"
    INVALID_RESULT = "INVALID_RESULT"
    QUERY_SESSION_TRANSITION_REJECTED = "QUERY_SESSION_TRANSITION_REJECTED"


class HealthFailure(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    HOST_UNAVAILABLE = "HOST_UNAVAILABLE"
    OUT_OF_SCOPE = "OUT_OF_SCOPE"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class RpcRejection(BaseModel):
    type: Literal["rejected"]
    failure: RpcFailure


class HealthError(BaseModel):
    code: HealthFailure


class HealthRejectionDocument(BaseModel):
    status: Literal["rejected"]
    error: HealthError


class HealthRejection(BaseModel):
    type: Literal["rejected_document"]
    document: HealthRejectionDocument


health_reply = TypeAdapter[HealthReply | RpcRejection | HealthRejection](
    HealthReply | RpcRejection | HealthRejection
)


class SetupFailure(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    WORKSPACE_UNAVAILABLE = "WORKSPACE_UNAVAILABLE"
    INSTALLATION_UNAVAILABLE = "INSTALLATION_UNAVAILABLE"
    PROCESS_FAILED = "PROCESS_FAILED"
    PREPARATION_TIMEOUT = "PREPARATION_TIMEOUT"
    READINESS_UNPROVEN = "READINESS_UNPROVEN"
    WORKSPACE_MISMATCH = "WORKSPACE_MISMATCH"


@dataclass(frozen=True, slots=True)
class Rejected:
    reason: SetupFailure | RpcFailure | HealthFailure


@dataclass(frozen=True, slots=True)
class Skipped:
    pass


@dataclass(frozen=True, slots=True)
class Prepared:
    root: Path


@dataclass(frozen=True, slots=True)
class KotlinWorkspace:
    root: Path


@dataclass(frozen=True, slots=True)
class RpcOutput:
    returncode: int
    stdout: str


type SetupOutcome = Prepared | Skipped | Rejected


class RpcExecutor(Protocol):
    def __call__(self, launcher: Path, workspace: KotlinWorkspace, /) -> RpcOutput | Rejected: ...


def discover(event: SessionStart) -> KotlinWorkspace | Skipped | Rejected:
    if event.source == "compact":
        return Skipped()
    directory = Path(event.cwd)
    if not directory.is_absolute():
        return Rejected(SetupFailure.INVALID_INPUT)
    try:
        directory = directory.resolve(strict=True)
        if not directory.is_dir():
            return Rejected(SetupFailure.WORKSPACE_UNAVAILABLE)
        for parent in (directory, *directory.parents):
            if (parent / "settings.gradle.kts").is_file():
                return KotlinWorkspace(parent)
            if (parent / ".git").exists():
                break
    except OSError:
        return Rejected(SetupFailure.WORKSPACE_UNAVAILABLE)
    return Skipped()


def invoke_rpc(launcher: Path, workspace: KotlinWorkspace) -> RpcOutput | Rejected:
    try:
        result = subprocess.run(
            [str(launcher), "call", "health_check"],
            cwd=workspace.root,
            input=HealthRequest().model_dump_json(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=PREPARATION_SECONDS,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return Rejected(SetupFailure.PREPARATION_TIMEOUT)
    except OSError:
        return Rejected(SetupFailure.PROCESS_FAILED)
    return RpcOutput(result.returncode, result.stdout)


def prepare(event: SessionStart, installation: Path, execute: RpcExecutor) -> SetupOutcome:
    workspace = discover(event)
    if not isinstance(workspace, KotlinWorkspace):
        return workspace
    launcher = installation / "bin/kast-tool-rpc-complete"
    if not launcher.is_file() or not os.access(launcher, os.X_OK):
        return Rejected(SetupFailure.INSTALLATION_UNAVAILABLE)
    result = execute(launcher, workspace)
    if isinstance(result, Rejected):
        return result
    if result.returncode != 0:
        return Rejected(SetupFailure.PROCESS_FAILED)
    try:
        reply = health_reply.validate_json(result.stdout)
    except ValidationError:
        return Rejected(SetupFailure.READINESS_UNPROVEN)
    if isinstance(reply, RpcRejection):
        return Rejected(reply.failure)
    if isinstance(reply, HealthRejection):
        return Rejected(reply.document.error.code)
    if reply.document.data.workspaceBinding != str(workspace.root):
        return Rejected(SetupFailure.WORKSPACE_MISMATCH)
    return Prepared(workspace.root)


def main() -> int:
    outcome: SetupOutcome
    raw = sys.stdin.buffer.read(MAXIMUM_INPUT + 1)
    try:
        event = SessionStart.model_validate_json(raw) if len(raw) <= MAXIMUM_INPUT else None
    except ValidationError:
        event = None
    if event is None:
        outcome = Rejected(SetupFailure.INVALID_INPUT)
    else:
        installation = Path.home() / ".local/share/kast/installation"
        outcome = prepare(event, installation, invoke_rpc)
    match outcome:
        case Prepared():
            print("Kast prepared this Kotlin Gradle workspace. IntelliJ reports indexed readiness.")
            return 0
        case Skipped():
            return 0
        case Rejected(reason):
            print(f"kast session setup: {reason.value}", file=sys.stderr)
            return 1


if __name__ == "__main__":
    raise SystemExit(main())
