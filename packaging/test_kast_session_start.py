"""Exercise session setup with owned files and a real RPC child adapter."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

import pytest
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "agent-tools/hooks"))
from session_start import (  # noqa: E402
    PREPARATION_SECONDS,
    KotlinWorkspace,
    Prepared,
    Rejected,
    RpcFailure,
    RpcOutput,
    SessionStart,
    SetupFailure,
    Skipped,
    discover,
    invoke_rpc,
    prepare,
)


class CommandHook(BaseModel):
    type: Literal["command"]
    command: str
    background: bool = Field(alias="async")
    timeout: int


class HookGroup(BaseModel):
    matcher: str
    hooks: list[CommandHook]


class HookConfiguration(BaseModel):
    hooks: dict[Literal["SessionStart"], list[HookGroup]]


@dataclass(frozen=True)
class FixtureData:
    workspaceBinding: str
    readiness: str = "READY"
    hostState: str = "INDEXED"
    contentView: str = "SAVED_PSI_COMMITTED"


@dataclass(frozen=True)
class FixtureDocument:
    data: FixtureData
    status: str = "complete"


@dataclass(frozen=True)
class FixtureReply:
    document: FixtureDocument
    type: str = "complete"


def response(root: Path, readiness: str = "READY") -> str:
    return json.dumps(asdict(FixtureReply(FixtureDocument(FixtureData(str(root), readiness)))))


def event(
    root: Path,
    source: Literal["startup", "resume", "fork", "clear", "compact"] = "startup",
) -> SessionStart:
    return SessionStart(hook_event_name="SessionStart", source=source, cwd=str(root))


def workspace(tmp_path: Path) -> Path:
    root = tmp_path / "repo $(literal); with spaces"
    root.mkdir()
    (root / ".git").write_text("gitdir: /unused", encoding="utf-8")
    (root / "settings.gradle.kts").write_text("", encoding="utf-8")
    return root.resolve()


def launcher(installation: Path, reply: str) -> Path:
    command = installation / "bin/kast-tool-rpc-complete"
    command.parent.mkdir(parents=True)
    command.write_text(
        f"#!{sys.executable}\n"
        "import json, sys\n"
        "assert sys.argv[1:] == ['call', 'health_check']\n"
        "assert json.load(sys.stdin) == {'verbose': True}\n"
        f"print({reply!r})\n",
        encoding="utf-8",
    )
    command.chmod(0o700)
    return command


def forbidden(_launcher: Path, _workspace: KotlinWorkspace) -> RpcOutput:
    raise AssertionError("RPC must not run for this input")


def test_direct_rpc_prepares_exact_workspace_with_literal_path(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    installation = tmp_path / "installation"
    launcher(installation, response(root))
    assert prepare(event(root), installation, invoke_rpc) == Prepared(root)


def test_subdirectory_selects_nearest_kotlin_gradle_root(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    child = root / "src/main"
    child.mkdir(parents=True)
    assert discover(event(child)) == KotlinWorkspace(root)
    (child.parent / "settings.gradle.kts").write_text("", encoding="utf-8")
    assert discover(event(child)) == KotlinWorkspace(child.parent)


def test_non_kotlin_repository_does_not_escape_git_root(tmp_path: Path) -> None:
    (tmp_path / "settings.gradle.kts").write_text("", encoding="utf-8")
    root = tmp_path / "other"
    root.mkdir()
    (root / ".git").mkdir()
    (root / "settings.gradle").write_text("", encoding="utf-8")
    assert prepare(event(root), tmp_path / "absent", forbidden) == Skipped()


def test_compaction_does_not_repeat_setup(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    assert prepare(event(root, "compact"), tmp_path / "absent", forbidden) == Skipped()


def test_missing_launcher_never_calls_rpc(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    assert prepare(event(root), tmp_path / "absent", forbidden) == Rejected(
        SetupFailure.INSTALLATION_UNAVAILABLE
    )


@pytest.mark.parametrize("reply", ["not JSON", '{"type":"rejected","failure":"UNKNOWN"}'])
def test_rejected_or_malformed_rpc_never_claims_readiness(tmp_path: Path, reply: str) -> None:
    root = workspace(tmp_path)
    installation = tmp_path / "installation"
    launcher(installation, reply)
    assert prepare(event(root), installation, invoke_rpc) == Rejected(
        SetupFailure.READINESS_UNPROVEN
    )


def test_unavailable_index_never_claims_readiness(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    installation = tmp_path / "installation"
    launcher(installation, response(root, "UNAVAILABLE"))
    assert prepare(event(root), installation, invoke_rpc) == Rejected(
        SetupFailure.READINESS_UNPROVEN
    )


def test_another_workspace_never_claims_readiness(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    installation = tmp_path / "installation"
    launcher(installation, response(tmp_path))
    assert prepare(event(root), installation, invoke_rpc) == Rejected(
        SetupFailure.WORKSPACE_MISMATCH
    )


def test_rpc_rejection_preserves_the_original_failure(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    installation = tmp_path / "installation"
    launcher(installation, '{"type":"rejected","failure":"OUT_OF_SCOPE"}')
    assert prepare(event(root), installation, invoke_rpc) == Rejected(RpcFailure.OUT_OF_SCOPE)


def test_process_exit_and_timeout_remain_failures(tmp_path: Path) -> None:
    root = workspace(tmp_path)
    installation = tmp_path / "installation"
    launcher(installation, response(root))

    def failed(_launcher: Path, _workspace: KotlinWorkspace) -> RpcOutput:
        return RpcOutput(17, response(root))

    def timed_out(_launcher: Path, _workspace: KotlinWorkspace) -> Rejected:
        return Rejected(SetupFailure.PREPARATION_TIMEOUT)

    assert prepare(event(root), installation, failed) == Rejected(SetupFailure.PROCESS_FAILED)
    assert prepare(event(root), installation, timed_out) == Rejected(
        SetupFailure.PREPARATION_TIMEOUT
    )


@pytest.mark.parametrize("raw", [b"not JSON", b"{}", b"x" * 65537])
def test_malformed_host_input_reports_failure_without_context(tmp_path: Path, raw: bytes) -> None:
    result = subprocess.run(
        [sys.executable, str(ROOT / "agent-tools/hooks/session_start.py")],
        input=raw,
        cwd=tmp_path,
        capture_output=True,
        check=False,
    )
    assert result.returncode == 1
    assert result.stdout == b""
    assert result.stderr == b"kast session setup: INVALID_INPUT\n"


def test_configured_hook_runs_outside_plugin_root(tmp_path: Path) -> None:
    import shlex

    root = workspace(tmp_path)
    home = tmp_path / "home"
    launcher(home / ".local/share/kast/installation", response(root))
    configuration = HookConfiguration.model_validate_json(
        (ROOT / "agent-tools/hooks/hooks.json").read_bytes()
    )
    [group] = configuration.hooks["SessionStart"]
    [handler] = group.hooks
    assert group.matcher == "^(startup|resume|fork|clear)$"
    assert handler.background
    assert handler.timeout > PREPARATION_SECONDS
    result = subprocess.run(
        shlex.split(handler.command.replace("${PLUGIN_ROOT}", str(ROOT / "agent-tools"))),
        input=event(root).model_dump_json(),
        text=True,
        cwd=tmp_path,
        env=os.environ | {"HOME": str(home), "PLUGIN_ROOT": str(ROOT / "agent-tools")},
        capture_output=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout == (
        "Kast prepared this Kotlin Gradle workspace. IntelliJ reports indexed readiness.\n"
    )
