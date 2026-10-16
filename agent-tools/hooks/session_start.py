#!/usr/bin/env python3
# /// script
# requires-python = ">=3.9,<3.15"
# dependencies = []
# [tool.uv]
# no-build = true
# ///
"""Read bundled Kast context without starting an IDE, tool server, or model turn."""

from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path
from typing import Literal, NoReturn, Union

JsonValue = Union[dict[str, "JsonValue"], list["JsonValue"], str, int, float, bool, None]

MAX_INPUT_BYTES = 65_536
MAX_CONTEXT_BYTES = 8_192
MAX_RESOURCE_BYTES = 262_144
RESOURCE_NAMES = ("onboarding.md", "query-schema.json", "query-examples.json")


class Host(Enum):
    CODEX = "codex"
    COPILOT = "copilot"
    PI = "pi"


class Failure(Enum):
    INVALID_EVENT = "INVALID_EVENT"
    RESOURCE_UNAVAILABLE = "RESOURCE_UNAVAILABLE"
    RESOURCE_REJECTED = "RESOURCE_REJECTED"
    CONTEXT_TOO_LARGE = "CONTEXT_TOO_LARGE"


@dataclass(frozen=True)
class Rejected:
    outcome: Failure


@dataclass(frozen=True)
class Context:
    text: str


@dataclass(frozen=True)
class CodexContext:
    additionalContext: str
    hookEventName: Literal["SessionStart"] = "SessionStart"


@dataclass(frozen=True)
class CodexOutput:
    hookSpecificOutput: CodexContext


@dataclass(frozen=True)
class CopilotOutput:
    additionalContext: str


@dataclass(frozen=True)
class Diagnostic:
    outcome: str
    stage: Literal["KAST_SESSION_CONTEXT"] = "KAST_SESSION_CONTEXT"


def workspace_from_event(raw: bytes) -> Path | Rejected:
    if len(raw) > MAX_INPUT_BYTES:
        return Rejected(Failure.INVALID_EVENT)
    try:
        event: JsonValue = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        return Rejected(Failure.INVALID_EVENT)
    if not isinstance(event, dict):
        return Rejected(Failure.INVALID_EVENT)
    cwd: object = event.get("cwd")
    return workspace_from_path(cwd)


def workspace_from_path(raw: object) -> Path | Rejected:
    if not isinstance(raw, str) or not raw or len(raw) > 4096 or any(ord(c) < 32 for c in raw):
        return Rejected(Failure.INVALID_EVENT)
    path = Path(raw)
    if not path.is_absolute():
        return Rejected(Failure.INVALID_EVENT)
    try:
        return path.resolve(strict=True) if path.is_dir() else Rejected(Failure.INVALID_EVENT)
    except (OSError, ValueError, RuntimeError):
        return Rejected(Failure.INVALID_EVENT)


def read_context(plugin: Path, workspace: Path) -> Context | Rejected:
    try:
        references = plugin.resolve(strict=True) / "references"
        if references.is_symlink():
            return Rejected(Failure.RESOURCE_REJECTED)
        for name in RESOURCE_NAMES:
            path = references / name
            if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_RESOURCE_BYTES:
                return Rejected(Failure.RESOURCE_REJECTED)
        brief = (references / "onboarding.md").read_text(encoding="utf-8")
        # Paths are JSON strings, so spaces and shell metacharacters remain literal context.
        text = (
            brief.rstrip()
            + "\nWorkspace: "
            + json.dumps(str(workspace), ensure_ascii=False)
            + "\nQuery schema: "
            + json.dumps(str(references / "query-schema.json"), ensure_ascii=False)
            + "\nQuery examples: "
            + json.dumps(str(references / "query-examples.json"), ensure_ascii=False)
            + "\n"
        )
    except (OSError, UnicodeError, RuntimeError):
        return Rejected(Failure.RESOURCE_UNAVAILABLE)
    if not brief.strip() or "{{" in brief:
        return Rejected(Failure.RESOURCE_REJECTED)
    if len(text.encode("utf-8")) > MAX_CONTEXT_BYTES:
        return Rejected(Failure.CONTEXT_TOO_LARGE)
    return Context(text)


def encode(host: Host, context: Context) -> str:
    if host is Host.CODEX:
        return json.dumps(asdict(CodexOutput(CodexContext(context.text))), ensure_ascii=False)
    if host is Host.COPILOT:
        return json.dumps(asdict(CopilotOutput(context.text)), ensure_ascii=False)
    if host is Host.PI:
        return context.text
    return unexpected_host(host)


def unexpected_host(host: NoReturn) -> NoReturn:
    raise AssertionError(f"Unsupported host: {host!r}")


def main(arguments: list[str]) -> int:
    if (
        len(arguments) not in (1, 2)
        or arguments[0] not in {host.value for host in Host}
        or len(arguments) == 2
        and arguments[0] != Host.PI.value
    ):
        result = Rejected(Failure.INVALID_EVENT)
    else:
        host = Host(arguments[0])
        workspace = (
            workspace_from_path(arguments[1])
            if len(arguments) == 2 and host is Host.PI
            else workspace_from_event(sys.stdin.buffer.read(MAX_INPUT_BYTES + 1))
        )
        result = (
            workspace
            if isinstance(workspace, Rejected)
            else read_context(Path(__file__).resolve().parent.parent, workspace)
        )
        if isinstance(result, Context):
            print(encode(host, result))
            return 0
    print(json.dumps(asdict(Diagnostic(result.outcome.value))), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
