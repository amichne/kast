#!/usr/bin/env python3
"""Read-only Kast install, package, and workspace verifier."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
RECOVERY = {
    "doctor": "kast doctor --repair",
    "skill": "kast install skill --force",
    "instructions": "kast install instructions --force",
    "copilot": "kast install copilot --force",
    "development": "./gradlew installDevelopmentLocal",
}
COPILOT_FILES = [
    "lsp.json",
    "extensions/kast/extension.mjs",
    "extensions/kast/_shared/kast-tools.mjs",
    "extensions/kast/_shared/kast-trace.mjs",
    "extensions/kast/_shared/commands.json",
    ".kast-copilot-version",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Emit JSON evidence for the active Kast binary, install state, and repo package files."
    )
    parser.add_argument("--workspace-root", type=Path, default=Path.cwd())
    parser.add_argument("--skill-root", type=Path)
    parser.add_argument("--kast-bin", type=Path)
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--require-gradle-project", action="store_true")
    parser.add_argument("--require-copilot", action="store_true")
    parser.add_argument("--require-skill", action="store_true")
    parser.add_argument("--require-instructions", action="store_true")
    return parser.parse_args()


def normalize(path: Path) -> Path:
    return path.expanduser().resolve()


def command_record(command: list[str], cwd: Path, timeout: int) -> dict[str, Any]:
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            text=True,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
        return {
            "command": command,
            "exitCode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "timedOut": False,
        }
    except FileNotFoundError as error:
        return {
            "command": command,
            "exitCode": 127,
            "stdout": "",
            "stderr": str(error),
            "timedOut": False,
        }
    except subprocess.TimeoutExpired as error:
        return {
            "command": command,
            "exitCode": 124,
            "stdout": error.stdout or "",
            "stderr": error.stderr or "",
            "timedOut": True,
        }


def parse_json_output(record: dict[str, Any]) -> Any | None:
    try:
        return json.loads(record.get("stdout", ""))
    except json.JSONDecodeError:
        return None


def help_lists_command(help_text: str, command: str) -> bool:
    for line in help_text.splitlines():
        stripped = line.strip()
        if stripped == command or stripped.startswith(f"{command} "):
            return True
    return False


def parse_cli_version(version_output: str) -> str | None:
    match = re.search(r"Kast CLI\s+(\S+)", version_output.strip())
    if match:
        return match.group(1)
    return version_output.strip() or None


def file_sha256(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8").strip()
    except OSError:
        return None


def add_issue(result: dict[str, Any], code: str, message: str, recovery: str | None = None) -> None:
    issue: dict[str, Any] = {"code": code, "message": message}
    if recovery:
        issue["recovery"] = recovery
        if recovery not in result["recovery"]:
            result["recovery"].append(recovery)
    result["issues"].append(issue)


def add_warning(result: dict[str, Any], code: str, message: str) -> None:
    result["warnings"].append({"code": code, "message": message})


def verify_command_surface(
    result: dict[str, Any],
    kast_command: list[str],
    workspace_root: Path,
    timeout: int,
) -> None:
    checks = result["checks"]
    top_help = command_record(kast_command + ["--help"], workspace_root, timeout)
    agent_help = command_record(kast_command + ["agent", "--help"], workspace_root, timeout)
    install_help = command_record(kast_command + ["install", "--help"], workspace_root, timeout)
    version = command_record(kast_command + ["version"], workspace_root, timeout)

    top_help_text = top_help["stdout"] + top_help["stderr"]
    install_help_text = install_help["stdout"] + install_help["stderr"]
    checks["commandSurface"] = {
        "helpExitCode": top_help["exitCode"],
        "agentHelpExitCode": agent_help["exitCode"],
        "installHelpExitCode": install_help["exitCode"],
        "versionExitCode": version["exitCode"],
        "version": version["stdout"].strip(),
        "cliVersion": parse_cli_version(version["stdout"]),
        "agentAvailable": agent_help["exitCode"] == 0,
        "rpcVisibleInTopHelp": help_lists_command(top_help_text, "rpc"),
        "installAffectedVisible": help_lists_command(install_help_text, "affected"),
    }

    if top_help["exitCode"] != 0:
        add_issue(result, "KAST_HELP_FAILED", "`kast --help` failed.", None)
    if agent_help["exitCode"] != 0:
        add_issue(
            result,
            "KAST_AGENT_UNAVAILABLE",
            "`kast agent --help` failed; the installed skill and active binary are out of sync.",
            RECOVERY["development"],
        )
    if checks["commandSurface"]["rpcVisibleInTopHelp"]:
        add_issue(
            result,
            "KAST_RPC_FIRST_CLASS",
            "`kast rpc` is visible in top-level help; expected the agent-first hidden surface.",
            RECOVERY["development"],
        )
    if checks["commandSurface"]["installAffectedVisible"]:
        add_issue(
            result,
            "KAST_INSTALL_AFFECTED_RETIRED",
            "`kast install affected` is visible; expected the v1 install surface.",
            RECOVERY["development"],
        )


def verify_doctor_and_paths(
    result: dict[str, Any],
    kast_command: list[str],
    workspace_root: Path,
    timeout: int,
) -> None:
    doctor = command_record(kast_command + ["--output", "json", "doctor"], workspace_root, timeout)
    doctor_json = parse_json_output(doctor)
    result["checks"]["doctor"] = {
        "exitCode": doctor["exitCode"],
        "parsed": doctor_json is not None,
        "ok": doctor_json.get("ok") if isinstance(doctor_json, dict) else None,
        "manifestPath": doctor_json.get("manifestPath") if isinstance(doctor_json, dict) else None,
        "binary": doctor_json.get("binary") if isinstance(doctor_json, dict) else None,
        "issues": doctor_json.get("issues", []) if isinstance(doctor_json, dict) else [],
        "warnings": doctor_json.get("warnings", []) if isinstance(doctor_json, dict) else [],
    }
    if doctor["exitCode"] != 0 or not isinstance(doctor_json, dict) or not doctor_json.get("ok", False):
        message = "`kast --output json doctor` did not prove a healthy install."
        if isinstance(doctor_json, dict) and doctor_json.get("issues"):
            message = f"{message} Issues: {doctor_json['issues']}"
        add_issue(result, "KAST_DOCTOR_UNHEALTHY", message, RECOVERY["doctor"])
    elif isinstance(doctor_json, dict) and doctor_json.get("warnings"):
        add_warning(
            result,
            "KAST_DOCTOR_WARNINGS",
            f"`kast --output json doctor` reported warnings: {doctor_json['warnings']}",
        )

    paths = command_record(
        kast_command + ["--output", "json", "paths", "--workspace-root", str(workspace_root)],
        workspace_root,
        timeout,
    )
    paths_json = parse_json_output(paths)
    result["checks"]["paths"] = {
        "exitCode": paths["exitCode"],
        "parsed": paths_json is not None,
        "root": paths_json.get("root") if isinstance(paths_json, dict) else None,
        "warnings": paths_json.get("warnings", []) if isinstance(paths_json, dict) else [],
    }
    if paths["exitCode"] != 0 or not isinstance(paths_json, dict):
        add_issue(
            result,
            "KAST_PATHS_UNAVAILABLE",
            "`kast --output json paths` failed; active binary may predate manifest-backed path inspection.",
            RECOVERY["development"],
        )
    elif paths_json.get("warnings"):
        add_warning(
            result,
            "KAST_PATHS_WARNINGS",
            f"`kast --output json paths` reported warnings: {paths_json['warnings']}",
        )


def verify_workspace(result: dict[str, Any], workspace_root: Path, require_gradle: bool) -> None:
    gradle_markers = [
        workspace_root / "settings.gradle.kts",
        workspace_root / "settings.gradle",
        workspace_root / "build.gradle.kts",
        workspace_root / "build.gradle",
        workspace_root / "gradlew",
    ]
    present = [str(path) for path in gradle_markers if path.exists()]
    result["checks"]["workspace"] = {
        "root": str(workspace_root),
        "exists": workspace_root.is_dir(),
        "gradleMarkers": present,
        "looksLikeGradle": bool(present),
    }
    if not workspace_root.is_dir():
        add_issue(result, "WORKSPACE_ROOT_MISSING", f"Workspace root is not a directory: {workspace_root}", None)
    elif require_gradle and not present:
        add_issue(
            result,
            "GRADLE_PROJECT_NOT_FOUND",
            "No Gradle project marker found before semantic work.",
            None,
        )


def verify_copilot(
    result: dict[str, Any],
    workspace_root: Path,
    source_catalog: Path,
    expected_version: str | None,
    required: bool,
) -> None:
    github_dir = workspace_root / ".github"
    files = {
        relative: {
            "path": str(github_dir / relative),
            "exists": (github_dir / relative).is_file(),
        }
        for relative in COPILOT_FILES
    }
    commands_path = github_dir / "extensions/kast/_shared/commands.json"
    source_hash = file_sha256(source_catalog)
    installed_hash = file_sha256(commands_path)
    marker_version = read_text(github_dir / ".kast-copilot-version")
    check = {
        "target": str(github_dir),
        "exists": github_dir.is_dir(),
        "files": files,
        "markerVersion": marker_version,
        "expectedVersion": expected_version,
        "markerMatchesExpected": bool(expected_version and marker_version == expected_version),
        "commandsHashMatchesSource": bool(source_hash and installed_hash and source_hash == installed_hash),
    }
    result["checks"]["copilotPackage"] = check
    missing = [relative for relative, info in files.items() if not info["exists"]]
    stale = source_hash and installed_hash and source_hash != installed_hash
    version_mismatch = expected_version and marker_version and expected_version != marker_version
    if required and (missing or stale or version_mismatch):
        add_issue(
            result,
            "COPILOT_PACKAGE_STALE",
            f"Repository Copilot package is missing or stale under {github_dir}.",
            RECOVERY["copilot"],
        )
    elif github_dir.is_dir() and (stale or version_mismatch):
        add_warning(
            result,
            "COPILOT_PACKAGE_STALE",
            f"Repository Copilot package differs from the installed skill source under {github_dir}.",
        )


def resource_targets(workspace_root: Path, kind: str) -> list[Path]:
    return [
        workspace_root / ".agents" / kind / "kast",
        workspace_root / ".github" / kind / "kast",
        workspace_root / ".claude" / kind / "kast",
    ]


def verify_resource_install(
    result: dict[str, Any],
    workspace_root: Path,
    expected_version: str | None,
    kind: str,
    required: bool,
    source_root: Path | None = None,
    content_files: list[str] | None = None,
) -> None:
    targets = []
    for target in resource_targets(workspace_root, kind):
        marker = read_text(target / ".kast-version")
        content_mismatches: list[str] = []
        if target.is_dir() and source_root and content_files:
            for relative in content_files:
                source_hash = file_sha256(source_root / relative)
                target_hash = file_sha256(target / relative)
                if source_hash and source_hash != target_hash:
                    content_mismatches.append(relative)
        targets.append(
            {
                "path": str(target),
                "exists": target.is_dir(),
                "markerVersion": marker,
                "expectedVersion": expected_version,
                "versionMatchesExpected": bool(marker and expected_version and marker == expected_version),
                "contentMismatches": content_mismatches,
            }
        )
    result["checks"][kind] = {"targets": targets}
    installed = [target for target in targets if target["exists"]]
    stale = [
        target
        for target in installed
        if (expected_version and target["markerVersion"] != expected_version)
        or target["contentMismatches"]
    ]
    if required and (not installed or stale):
        recovery = RECOVERY["skill"] if kind == "skills" else RECOVERY["instructions"]
        add_issue(
            result,
            f"{kind.upper()}_STALE",
            f"No current repository-local Kast {kind} install was found.",
            recovery,
        )
    elif stale:
        recovery = RECOVERY["skill"] if kind == "skills" else RECOVERY["instructions"]
        add_warning(
            result,
            f"{kind.upper()}_STALE",
            f"A repository-local Kast {kind} install exists but does not match the current expected state. Recovery: {recovery}",
        )


def main() -> int:
    args = parse_args()
    workspace_root = normalize(args.workspace_root)
    script_root = Path(__file__).resolve().parents[1]
    skill_root = normalize(args.skill_root) if args.skill_root else script_root
    source_catalog = skill_root / "references" / "commands.json"
    source_content_marker = read_text(skill_root / ".kast-version")

    which_kast = shutil.which("kast")
    if args.kast_bin:
        kast_bin = str(normalize(args.kast_bin))
    elif which_kast:
        kast_bin = which_kast
    else:
        kast_bin = None

    result: dict[str, Any] = {
        "type": "KAST_STATE_VERIFICATION",
        "schemaVersion": SCHEMA_VERSION,
        "ok": False,
        "workspaceRoot": str(workspace_root),
        "skillRoot": str(skill_root),
        "checks": {},
        "issues": [],
        "warnings": [],
        "recovery": [],
    }

    result["checks"]["sourceSkill"] = {
        "catalog": str(source_catalog),
        "catalogExists": source_catalog.is_file(),
        "contentMarker": source_content_marker,
        "scripts": {
            "verifyKastState": str(skill_root / "scripts/verify-kast-state.py"),
            "kastAgentCall": str(skill_root / "scripts/kast-agent-call.py"),
            "kastSemanticWorkflow": str(skill_root / "scripts/kast-semantic-workflow.py"),
        },
    }
    if not source_catalog.is_file():
        add_issue(result, "SOURCE_CATALOG_MISSING", f"Missing skill command catalog: {source_catalog}", None)

    result["checks"]["binaryResolution"] = {
        "pathLookup": which_kast,
        "selected": kast_bin,
    }
    if not kast_bin:
        add_issue(result, "KAST_NOT_FOUND", "`kast` was not found on PATH.", RECOVERY["doctor"])
    else:
        kast_command = [kast_bin]
        verify_command_surface(result, kast_command, workspace_root, args.timeout)
        verify_doctor_and_paths(result, kast_command, workspace_root, args.timeout)

    expected_version = result["checks"].get("commandSurface", {}).get("cliVersion")
    verify_workspace(result, workspace_root, args.require_gradle_project)
    verify_copilot(result, workspace_root, source_catalog, expected_version, args.require_copilot)
    verify_resource_install(
        result,
        workspace_root,
        expected_version,
        "skills",
        args.require_skill,
        skill_root,
        [
            "SKILL.md",
            "references/commands.json",
            "references/quickstart.md",
            "references/runbook.md",
            "references/workflows.md",
            "scripts/verify-kast-state.py",
            "scripts/kast-agent-call.py",
            "scripts/kast-semantic-workflow.py",
        ],
    )
    verify_resource_install(
        result,
        workspace_root,
        expected_version,
        "instructions",
        args.require_instructions,
    )

    result["ok"] = not result["issues"]
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
