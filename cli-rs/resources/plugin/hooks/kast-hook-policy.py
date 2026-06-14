#!/usr/bin/env python3
"""Compact Copilot hook policy for Kast-backed Kotlin work."""

from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

DEFAULT_MAX_READ_BYTES = 40_000
BLOCK_EXIT = 2


@dataclass(frozen=True)
class Decision:
    action: str
    reason: str
    alternatives: tuple[str, ...] = ()
    details: dict[str, Any] | None = None

    @staticmethod
    def allow(reason: str, details: dict[str, Any] | None = None) -> "Decision":
        return Decision(action="allow", reason=reason, details=details)

    @staticmethod
    def block(
        reason: str,
        alternatives: tuple[str, ...],
        details: dict[str, Any] | None = None,
    ) -> "Decision":
        return Decision(action="block", reason=reason, alternatives=alternatives, details=details)


def main() -> int:
    event = sys.argv[1] if len(sys.argv) > 1 else ""
    payload = read_payload()
    repo_root = Path(os.environ.get("KAST_HOOK_REPO_ROOT", os.getcwd())).resolve()
    state_dir = Path(os.environ.get("KAST_HOOK_STATE_DIR", repo_root / ".agent-turn" / "kast-hooks")).resolve()
    state_file = state_dir / "state.json"

    if event == "sessionStart":
        state_dir.mkdir(parents=True, exist_ok=True)
        write_json(state_file, initial_state())
        return emit(Decision.allow("Kast hook state initialized", {"stateFile": str(state_file)}))
    if event == "preToolUse":
        return emit(pre_tool_use(payload, repo_root))
    if event == "postToolUse":
        return emit(post_tool_use(payload, repo_root, state_file))
    if event == "sessionEnd":
        return emit(session_end(state_file))
    return emit(Decision.block(
        f"Unknown hook event: {event}",
        ("Check .github/hooks/hooks.json for supported Kast hook events.",),
    ))


def read_payload() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        decoded = json.loads(raw)
    except json.JSONDecodeError as error:
        return {"_invalidJson": str(error), "_raw": raw[:500]}
    return decoded if isinstance(decoded, dict) else {"value": decoded}


def initial_state() -> dict[str, Any]:
    return {
        "changedFiles": [],
        "changedFileSummary": None,
        "validation": {"status": "missing", "ran": False},
        "symbolResolved": False,
    }


def pre_tool_use(payload: dict[str, Any], repo_root: Path) -> Decision:
    if payload.get("_invalidJson"):
        return Decision.block(
            "Hook input was not valid JSON.",
            ("Retry the tool call after the hook runner provides valid JSON input.",),
            {"error": payload["_invalidJson"]},
        )
    if has_override(payload):
        return Decision.allow("Explicit Kast hook override present.")

    tool_name = normalize_tool_name(payload)
    command = normalize_command(payload)
    paths = normalize_paths(payload, repo_root)
    edit_like = is_edit_like(tool_name, command, payload)

    dangerous = dangerous_command_reason(command)
    if dangerous:
        return Decision.block(
            dangerous,
            ("Ask the user for explicit approval before destructive shell operations.",),
            {"command": command},
        )

    if is_broad_kotlin_search(command):
        return Decision.block(
            "Broad text search over Kotlin sources is blocked when Kast/LSP symbol navigation is available.",
            (
                "Use LSP workspace/symbol, textDocument/definition, textDocument/references, or call hierarchy.",
                "If LSP is unavailable, use kast_workspace_symbol, kast_references, or kast rpc.",
            ),
            {"command": command},
        )

    if is_broad_file_enumeration(command):
        return Decision.block(
            "Broad recursive repository enumeration is blocked by Kast hooks.",
            (
                "Use LSP document/workspace symbols for Kotlin discovery.",
                "Use targeted rg --files globs for non-Kotlin path discovery.",
            ),
            {"command": command},
        )

    large_paths = large_read_paths(tool_name, command, paths, repo_root)
    if large_paths:
        return Decision.block(
            "Full-file reads above the configured size threshold are blocked.",
            (
                "Use a targeted symbol/diagnostics request or a bounded line range.",
                "Write large evidence to an artifact path and summarize it compactly.",
            ),
            {"paths": [str(path) for path in large_paths]},
        )

    if edit_like:
        generated = [path for path in paths if is_generated_path(path, repo_root)]
        if generated:
            return Decision.block(
                "Edits to generated/build output paths are blocked.",
                ("Regenerate the owning source artifact instead of editing generated output.",),
                {"paths": [str(path) for path in generated]},
            )
        public_api = [path for path in paths if is_public_api_path(path, repo_root)]
        if public_api and not explicit_api_intent(payload):
            return Decision.block(
                "Public API surface edits require explicit user intent.",
                ("Confirm the API change, then rerun with explicitApiChange=true or KAST_HOOK_ALLOW_API_CHANGE=1.",),
                {"paths": [str(path) for path in public_api]},
            )
        if is_kotlin_rename_like(command, paths) and not symbol_resolved(payload):
            return Decision.block(
                "Kotlin rename-like edits require prior symbol resolution and reference enumeration.",
                (
                    "Use LSP definition/references or kast rpc raw/resolve plus raw/references before editing.",
                    "Prefer LSP rename or raw/rename when write-capable support is available.",
                ),
                {"paths": [str(path) for path in paths]},
            )

    return Decision.allow(
        "Tool use allowed by Kast hook policy.",
        {"tool": tool_name, "paths": [str(path) for path in paths]},
    )


def post_tool_use(payload: dict[str, Any], repo_root: Path, state_file: Path) -> Decision:
    state = read_state(state_file)
    paths = normalize_paths(payload, repo_root)
    edited_paths = [path for path in paths if path.exists() or path.suffix]
    if edited_paths:
        changed = sorted({*state.get("changedFiles", []), *[str(path) for path in edited_paths]})
        state["changedFiles"] = changed
        state["changedFileSummary"] = summarize_changed_files([Path(path) for path in changed], repo_root)
        if any(path.suffix in {".kt", ".kts"} for path in edited_paths):
            state["validation"] = run_or_record_diagnostics(edited_paths, repo_root)
        elif state.get("validation", {}).get("status") == "missing":
            state["validation"] = {"status": "not_required", "ran": False}
    write_json(state_file, state)
    return Decision.allow(
        "Post-tool Kast state captured.",
        {
            "stateFile": str(state_file),
            "changedFileSummary": state.get("changedFileSummary"),
            "validation": compact_validation(state.get("validation", {})),
        },
    )


def session_end(state_file: Path) -> Decision:
    state = read_state(state_file)
    changed = state.get("changedFiles", [])
    validation = state.get("validation", {})
    if changed and not state.get("changedFileSummary"):
        return Decision.block(
            "Completion blocked because changed files were not summarized.",
            ("Run the postToolUse hook or summarize changed files before final response.",),
            {"stateFile": str(state_file)},
        )
    kotlin_changed = any(str(path).endswith((".kt", ".kts")) for path in changed)
    if kotlin_changed and validation.get("status") not in {"clean", "not_required"}:
        if os.environ.get("KAST_HOOK_ALLOW_UNVALIDATED") == "1":
            return Decision.allow("Unvalidated Kotlin completion allowed by explicit override.")
        return Decision.block(
            "Completion blocked because Kotlin edits lack clean Kast diagnostics.",
            (
                "Run kast diagnostics for changed Kotlin files.",
                "Report any unavailable diagnostics as a blocker instead of claiming completion.",
            ),
            {"validation": compact_validation(validation), "stateFile": str(state_file)},
        )
    return Decision.allow("Kast hook completion gate passed.", {"validation": compact_validation(validation)})


def run_or_record_diagnostics(paths: list[Path], repo_root: Path) -> dict[str, Any]:
    if os.environ.get("KAST_HOOK_RUN_DIAGNOSTICS", "1") == "0":
        return {"status": "skipped", "ran": False, "reason": "KAST_HOOK_RUN_DIAGNOSTICS=0"}
    kotlin_paths = [str(path.resolve()) for path in paths if path.suffix in {".kt", ".kts"}]
    request = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "raw/diagnostics",
        "params": {"filePaths": kotlin_paths},
    }
    kast_bin = os.environ.get("KAST_HOOK_KAST_BIN", "kast")
    timeout = float(os.environ.get("KAST_HOOK_DIAGNOSTICS_TIMEOUT_SEC", "20"))
    try:
        completed = subprocess.run(
            [kast_bin, "rpc", json.dumps(request), "--workspace-root", str(repo_root)],
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"status": "unavailable", "ran": False, "reason": str(error), "fileCount": len(kotlin_paths)}
    if completed.returncode != 0:
        return {
            "status": "failed",
            "ran": True,
            "exitCode": completed.returncode,
            "stderr": completed.stderr[-500:],
            "fileCount": len(kotlin_paths),
        }
    try:
        response = json.loads(completed.stdout)
    except json.JSONDecodeError:
        return {"status": "failed", "ran": True, "reason": "diagnostics response was not JSON"}
    if "error" in response:
        return {
            "status": "failed",
            "ran": True,
            "errorCode": response.get("error", {}).get("data", {}).get("code"),
            "message": response.get("error", {}).get("message"),
            "fileCount": len(kotlin_paths),
        }
    diagnostics = response.get("result", {}).get("diagnostics", [])
    errors = [item for item in diagnostics if item.get("severity") == "ERROR"]
    warnings = [item for item in diagnostics if item.get("severity") == "WARNING"]
    return {
        "status": "clean" if not errors else "dirty",
        "ran": True,
        "errorCount": len(errors),
        "warningCount": len(warnings),
        "fileCount": len(kotlin_paths),
    }


def summarize_changed_files(paths: list[Path], repo_root: Path) -> dict[str, Any]:
    return {
        "total": len(paths),
        "kotlin": sum(path.suffix in {".kt", ".kts"} for path in paths),
        "generated": sum(is_generated_path(path, repo_root) for path in paths),
        "publicApi": sum(is_public_api_path(path, repo_root) for path in paths),
        "tests": sum("/src/test/" in path.as_posix() or path.name.endswith("Test.kt") for path in paths),
        "production": sum("/src/main/" in path.as_posix() for path in paths),
    }


def compact_validation(validation: dict[str, Any]) -> dict[str, Any]:
    return {key: validation.get(key) for key in ("status", "ran", "errorCount", "warningCount", "fileCount", "reason", "errorCode") if key in validation}


def normalize_tool_name(payload: dict[str, Any]) -> str:
    candidates = [
        payload.get("toolName"),
        payload.get("tool"),
        payload.get("name"),
        nested(payload, "tool", "name"),
    ]
    for value in candidates:
        if isinstance(value, str) and value:
            return value
    return "unknown"


def normalize_command(payload: dict[str, Any]) -> str:
    for key_path in [
        ("command",),
        ("input", "command"),
        ("toolInput", "command"),
        ("args", "command"),
        ("parameters", "command"),
    ]:
        value = nested(payload, *key_path)
        if isinstance(value, str):
            return value
    return ""


def normalize_paths(payload: dict[str, Any], repo_root: Path) -> list[Path]:
    values: list[str] = []
    collect_paths(payload, values)
    command = normalize_command(payload)
    values.extend(extract_paths_from_command(command))
    paths: list[Path] = []
    for value in values:
        if not value or value.startswith("-"):
            continue
        path = Path(value)
        if not path.is_absolute():
            path = repo_root / path
        paths.append(path.resolve())
    return sorted(set(paths))


def collect_paths(value: Any, output: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"path", "file", "filePath", "target", "uri"} and isinstance(child, str):
                output.append(strip_file_uri(child))
            elif key in {"paths", "files", "filePaths"} and isinstance(child, list):
                output.extend(strip_file_uri(item) for item in child if isinstance(item, str))
            else:
                collect_paths(child, output)
    elif isinstance(value, list):
        for child in value:
            collect_paths(child, output)


def extract_paths_from_command(command: str) -> list[str]:
    if not command:
        return []
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        tokens = command.split()
    paths: list[str] = []
    for token in tokens:
        candidate = shell_path_token(token)
        if candidate is not None:
            paths.append(candidate)
    return paths


def shell_path_token(token: str) -> str | None:
    candidate = token.strip().strip(",;:")
    if not candidate or candidate.startswith("-") or "/" not in candidate:
        return None
    if is_shell_pattern_expression(candidate):
        return None
    if candidate.startswith(("http://", "https://")):
        return None
    return candidate


def is_shell_pattern_expression(value: str) -> bool:
    return value.startswith(("s/", "m/", "y/", "tr/"))


def strip_file_uri(value: str) -> str:
    return value.removeprefix("file://")


def nested(value: Any, *keys: str) -> Any:
    current = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def has_override(payload: dict[str, Any]) -> bool:
    return os.environ.get("KAST_HOOK_OVERRIDE") == "allow" or payload.get("kastHookOverride") == "allow"


def explicit_api_intent(payload: dict[str, Any]) -> bool:
    return os.environ.get("KAST_HOOK_ALLOW_API_CHANGE") == "1" or payload.get("explicitApiChange") is True


def symbol_resolved(payload: dict[str, Any]) -> bool:
    return os.environ.get("KAST_HOOK_SYMBOL_RESOLVED") == "1" or payload.get("symbolResolved") is True


def is_edit_like(tool_name: str, command: str, payload: dict[str, Any]) -> bool:
    lowered = f"{tool_name} {command}".lower()
    if payload.get("edited") is True or payload.get("mutation") is True:
        return True
    return any(token in lowered for token in ("edit", "write", "create", "apply_patch", "sed -i", "perl -pi", ">"))


def dangerous_command_reason(command: str) -> str | None:
    if not command:
        return None
    patterns = [
        (r"\brm\s+-rf\s+/(?:\s|$)", "Recursive removal from filesystem root is blocked."),
        (r"\bgit\s+reset\s+--hard\b", "git reset --hard is blocked without explicit approval."),
        (r"\bgit\s+checkout\s+--\b", "git checkout -- is blocked because it can discard user work."),
        (r"\bgit\s+clean\s+-[A-Za-z]*f", "git clean with force is blocked without explicit approval."),
        (r"\bsudo\b", "sudo commands are blocked in agent hooks."),
    ]
    for pattern, reason in patterns:
        if re.search(pattern, command):
            return reason
    return None


def is_broad_kotlin_search(command: str) -> bool:
    if not re.search(r"(^|[;&|()\s])(rg|grep)(\s|$)", command):
        return False
    if re.search(r"(\.kt\b|\.kts\b|src/(main|test)/kotlin|--glob\s+['\"]?\*\*?/\*\.kt)", command):
        return True
    return bool(re.search(r"(^|\s)(rg|grep)\s+['\"]?\w+['\"]?\s+(\.|/Users/|\$PWD)", command))


def is_broad_file_enumeration(command: str) -> bool:
    if re.search(r"(^|\s)find\s+(\.|/Users/|\$PWD)(\s|$)", command) and "-maxdepth" not in command:
        return True
    return bool(re.search(r"(^|\s)(rg|fd)\s+--files\s*(\.|/Users/|\$PWD)?\s*$", command))


def large_read_paths(tool_name: str, command: str, paths: list[Path], repo_root: Path) -> list[Path]:
    lowered = f"{tool_name} {command}".lower()
    if not any(token in lowered for token in ("read", "cat", "view")):
        return []
    max_bytes = int(os.environ.get("KAST_HOOK_MAX_READ_BYTES", str(DEFAULT_MAX_READ_BYTES)))
    large: list[Path] = []
    for path in paths:
        try:
            if path.is_file() and path.stat().st_size > max_bytes and path.is_relative_to(repo_root):
                large.append(path)
        except OSError:
            continue
    return large


def is_generated_path(path: Path, repo_root: Path) -> bool:
    relative = relative_posix(path, repo_root)
    return any(
        token in relative
        for token in (
            "/build/",
            "/target/",
            "/site/",
            "/generated/",
            "/.gradle/",
            "/.agent-turn/",
        )
    ) or relative.startswith(("build/", "target/", "site/"))


def is_public_api_path(path: Path, repo_root: Path) -> bool:
    relative = relative_posix(path, repo_root)
    return relative.startswith("analysis-api/src/main/kotlin/") or relative in {
        "docs/openapi.yaml",
        "docs/reference/api-specification.md",
        "cli-rs/resources/kast-skill/references/commands.json",
        "cli-rs/resources/plugin/lsp.json",
        "cli-rs/resources/plugin/hooks/hooks.json",
    }


def is_kotlin_rename_like(command: str, paths: list[Path]) -> bool:
    if not any(path.suffix in {".kt", ".kts"} for path in paths):
        return False
    return bool(re.search(r"\b(rename|sed\s+-i|perl\s+-pi|replace)\b", command, re.IGNORECASE))


def relative_posix(path: Path, repo_root: Path) -> str:
    try:
        return path.resolve().relative_to(repo_root).as_posix()
    except ValueError:
        return path.as_posix()


def read_state(state_file: Path) -> dict[str, Any]:
    if not state_file.is_file():
        return initial_state()
    try:
        state = json.loads(state_file.read_text())
    except (OSError, json.JSONDecodeError):
        return initial_state()
    return state if isinstance(state, dict) else initial_state()


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    tmp.replace(path)


def emit(decision: Decision) -> int:
    payload: dict[str, Any] = {
        "ok": decision.action == "allow",
        "action": decision.action,
        "reason": decision.reason,
    }
    if decision.alternatives:
        payload["alternatives"] = list(decision.alternatives)
    if decision.details:
        payload["details"] = decision.details
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0 if decision.action == "allow" else BLOCK_EXIT


if __name__ == "__main__":
    raise SystemExit(main())
