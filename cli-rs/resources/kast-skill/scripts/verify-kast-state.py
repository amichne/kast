#!/usr/bin/env python3
"""Read-only Kast install, package, and workspace verifier."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1


def setup_recovery_command(
    kast_executable: str | None,
    target_dir: Path | None = None,
) -> str:
    executable = kast_executable or "kast"
    command = [executable, "setup"]
    if target_dir is not None:
        command.extend(["--skill-target-dir", str(target_dir)])
    command.append("--force")
    return shlex.join(command)


def recovery_commands(kast_executable: str | None) -> dict[str, str]:
    executable = kast_executable or "kast"
    return {
        "ready": shlex.join([executable, "repair", "--apply"]),
        "skill": setup_recovery_command(kast_executable),
        "development": "./gradlew installDevelopmentLocal",
    }


RECOVERY = recovery_commands("kast")
COPILOT_FILES = [
    "lsp.json",
    "extensions/kast/extension.mjs",
    "extensions/kast/_shared/kast-tools.mjs",
    "extensions/kast/_shared/kast-trace.mjs",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Emit JSON evidence for the active Kast binary, install state, and repo package files."
    )
    parser.add_argument("--workspace-root", type=Path, default=Path.cwd())
    parser.add_argument("--skill-root", type=Path)
    parser.add_argument(
        "--skill-target-dir",
        type=Path,
        action="append",
        default=[],
        help="Additional manifest-backed skill target directory to verify.",
    )
    parser.add_argument("--kast-bin", type=Path)
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--require-gradle-project", action="store_true")
    parser.add_argument("--require-skill", action="store_true")
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


def removed_command_envelope(envelope: Any, method: str) -> bool:
    if not isinstance(envelope, dict):
        return False
    error = envelope.get("error")
    return (
        envelope.get("ok") is False
        and envelope.get("method") == method
        and isinstance(error, dict)
        and error.get("code") == "AGENT_COMMAND_REMOVED"
    )


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
    ready_help = command_record(kast_command + ["ready", "--help"], workspace_root, timeout)
    repair_help = command_record(kast_command + ["repair", "--help"], workspace_root, timeout)
    agent_help = command_record(kast_command + ["agent", "--help"], workspace_root, timeout)
    agent_verify_help = command_record(
        kast_command + ["agent", "verify", "--help"],
        workspace_root,
        timeout,
    )
    agent_symbol_help = command_record(
        kast_command + ["agent", "symbol", "--help"],
        workspace_root,
        timeout,
    )
    agent_rename_help = command_record(
        kast_command + ["agent", "rename", "--help"],
        workspace_root,
        timeout,
    )
    agent_tools = command_record(
        kast_command + ["--output", "json", "agent", "tools", "--full"],
        workspace_root,
        timeout,
    )
    agent_call = command_record(
        kast_command + ["--output", "json", "agent", "call", "health"],
        workspace_root,
        timeout,
    )
    agent_workflow = command_record(
        kast_command + ["--output", "json", "agent", "workflow", "verify"],
        workspace_root,
        timeout,
    )
    setup_help = command_record(kast_command + ["setup", "--help"], workspace_root, timeout)
    install_help = command_record(kast_command + ["install", "--help"], workspace_root, timeout)
    version = command_record(kast_command + ["version"], workspace_root, timeout)

    top_help_text = top_help["stdout"] + top_help["stderr"]
    agent_help_text = agent_help["stdout"] + agent_help["stderr"]
    install_help_text = install_help["stdout"] + install_help["stderr"]
    agent_tools_json = parse_json_output(agent_tools)
    agent_call_json = parse_json_output(agent_call)
    agent_workflow_json = parse_json_output(agent_workflow)
    agent_tools_removed = removed_command_envelope(agent_tools_json, "agent/tools")
    agent_call_removed = removed_command_envelope(agent_call_json, "agent/call")
    agent_workflow_removed = removed_command_envelope(agent_workflow_json, "agent/workflow")
    checks["commandSurface"] = {
        "helpExitCode": top_help["exitCode"],
        "readyHelpExitCode": ready_help["exitCode"],
        "repairHelpExitCode": repair_help["exitCode"],
        "agentHelpExitCode": agent_help["exitCode"],
        "agentVerifyHelpExitCode": agent_verify_help["exitCode"],
        "agentSymbolHelpExitCode": agent_symbol_help["exitCode"],
        "agentRenameHelpExitCode": agent_rename_help["exitCode"],
        "agentToolsExitCode": agent_tools["exitCode"],
        "agentCallExitCode": agent_call["exitCode"],
        "agentWorkflowExitCode": agent_workflow["exitCode"],
        "setupHelpExitCode": setup_help["exitCode"],
        "installHelpExitCode": install_help["exitCode"],
        "versionExitCode": version["exitCode"],
        "version": version["stdout"].strip(),
        "cliVersion": parse_cli_version(version["stdout"]),
        "readyAvailable": ready_help["exitCode"] == 0,
        "repairAvailable": repair_help["exitCode"] == 0,
        "agentAvailable": agent_help["exitCode"] == 0,
        "agentVerifyAvailable": agent_verify_help["exitCode"] == 0,
        "agentSymbolAvailable": agent_symbol_help["exitCode"] == 0,
        "agentRenameAvailable": agent_rename_help["exitCode"] == 0,
        "agentToolsRemoved": agent_tools_removed,
        "agentCallRemoved": agent_call_removed,
        "agentWorkflowRemoved": agent_workflow_removed,
        "setupAvailable": setup_help["exitCode"] == 0,
        "agentHelpListsTools": help_lists_command(agent_help_text, "tools"),
        "agentHelpListsCall": help_lists_command(agent_help_text, "call"),
        "agentHelpListsWorkflow": help_lists_command(agent_help_text, "workflow"),
        "rpcVisibleInTopHelp": help_lists_command(top_help_text, "rpc"),
        "installVisibleInTopHelp": help_lists_command(top_help_text, "install"),
        "installAffectedVisible": help_lists_command(install_help_text, "affected"),
    }

    if top_help["exitCode"] != 0:
        add_issue(result, "KAST_HELP_FAILED", "`kast --help` failed.", None)
    if agent_help["exitCode"] != 0:
        add_issue(
            result,
            "KAST_AGENT_UNAVAILABLE",
            "`kast agent --help` failed; the installed skill and active binary are incompatible. Upgrade or reinstall Kast.",
            RECOVERY["development"],
        )
    if ready_help["exitCode"] != 0:
        add_issue(
            result,
            "KAST_READY_UNAVAILABLE",
            "`kast ready --help` failed; the installed skill and active binary are incompatible. Upgrade or reinstall Kast.",
            RECOVERY["development"],
        )
    if repair_help["exitCode"] != 0:
        add_issue(
            result,
            "KAST_REPAIR_UNAVAILABLE",
            "`kast repair --help` failed; the installed skill and active binary are incompatible. Upgrade or reinstall Kast.",
            RECOVERY["development"],
        )
    for code, label, record in [
        ("KAST_AGENT_VERIFY_UNAVAILABLE", "`kast agent verify --help`", agent_verify_help),
        ("KAST_AGENT_SYMBOL_UNAVAILABLE", "`kast agent symbol --help`", agent_symbol_help),
        ("KAST_AGENT_RENAME_UNAVAILABLE", "`kast agent rename --help`", agent_rename_help),
    ]:
        if record["exitCode"] != 0:
            add_issue(
                result,
                code,
                f"{label} failed; the installed skill and active binary are incompatible. Upgrade or reinstall Kast.",
                RECOVERY["development"],
            )
    for code, label, removed in [
        ("KAST_AGENT_TOOLS_STILL_PUBLIC", "`kast agent tools`", agent_tools_removed),
        ("KAST_AGENT_CALL_STILL_PUBLIC", "`kast agent call`", agent_call_removed),
        ("KAST_AGENT_WORKFLOW_STILL_PUBLIC", "`kast agent workflow`", agent_workflow_removed),
    ]:
        if not removed:
            add_issue(
                result,
                code,
                f"{label} did not return an AGENT_COMMAND_REMOVED envelope.",
                RECOVERY["development"],
            )
    if checks["commandSurface"]["agentHelpListsTools"] or checks["commandSurface"]["agentHelpListsCall"] or checks["commandSurface"]["agentHelpListsWorkflow"]:
        add_issue(
            result,
            "KAST_AGENT_REMOVED_COMMAND_VISIBLE",
            "`kast agent --help` still lists a removed generic agent command.",
            RECOVERY["development"],
        )
    if setup_help["exitCode"] != 0:
        add_issue(
            result,
            "KAST_SETUP_UNAVAILABLE",
            "`kast setup --help` failed; the installed skill and active binary are incompatible. Upgrade or reinstall Kast.",
            RECOVERY["development"],
        )
    if checks["commandSurface"]["rpcVisibleInTopHelp"]:
        add_issue(
            result,
            "KAST_RPC_FIRST_CLASS",
            "`kast rpc` is visible in top-level help; expected the removed shell RPC surface to stay unavailable.",
            RECOVERY["development"],
        )
    if checks["commandSurface"]["installVisibleInTopHelp"]:
        add_issue(
            result,
            "KAST_INSTALL_FIRST_CLASS",
            "`kast install` is visible in top-level help; expected the intent-first `kast setup` surface.",
            RECOVERY["development"],
        )
    if checks["commandSurface"]["installAffectedVisible"]:
        add_issue(
            result,
            "KAST_INSTALL_AFFECTED_RETIRED",
            "`kast install affected` is visible; expected the intent-first command surface.",
            RECOVERY["development"],
        )


def verify_ready_and_paths(
    result: dict[str, Any],
    kast_command: list[str],
    workspace_root: Path,
    timeout: int,
    tolerate_resource_output_issues: bool = False,
) -> dict[str, Any] | None:
    ready = command_record(kast_command + ["--output", "json", "ready"], workspace_root, timeout)
    ready_json = parse_json_output(ready)
    ready_issues = ready_json.get("issues", []) if isinstance(ready_json, dict) else []
    tolerated_resource_issues = (
        tolerate_resource_output_issues
        and isinstance(ready_json, dict)
        and resource_output_ready_issues(ready_issues)
    )
    result["checks"]["ready"] = {
        "exitCode": ready["exitCode"],
        "parsed": ready_json is not None,
        "ok": ready_json.get("ok") if isinstance(ready_json, dict) else None,
        "manifestPath": ready_json.get("manifestPath") if isinstance(ready_json, dict) else None,
        "binary": ready_json.get("binary") if isinstance(ready_json, dict) else None,
        "issues": ready_issues,
        "warnings": ready_json.get("warnings", []) if isinstance(ready_json, dict) else [],
        "resourceOutputIssuesTolerated": tolerated_resource_issues,
    }
    if ready["exitCode"] != 0 or not isinstance(ready_json, dict) or not ready_json.get("ok", False):
        if tolerated_resource_issues:
            add_warning(
                result,
                "KAST_READY_RESOURCE_OUTPUTS",
                "`kast --output json ready` reported manifest resource output issues; explicit --skill-root verification checks the selected skill target separately.",
            )
        else:
            message = "`kast --output json ready` did not prove a healthy install."
            if isinstance(ready_json, dict) and ready_json.get("issues"):
                message = f"{message} Issues: {ready_json['issues']}"
            add_issue(result, "KAST_READY_UNHEALTHY", message, RECOVERY["ready"])
    elif isinstance(ready_json, dict) and ready_json.get("warnings"):
        add_warning(
            result,
            "KAST_READY_WARNINGS",
            f"`kast --output json ready` reported warnings: {ready_json['warnings']}",
        )

    paths = command_record(
        kast_command + ["--output", "json", "developer", "inspect", "paths", "--workspace-root", str(workspace_root)],
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
            "`kast --output json developer inspect paths` failed; active binary may predate manifest-backed path inspection.",
            RECOVERY["development"],
        )
    elif paths_json.get("warnings"):
        add_warning(
            result,
            "KAST_PATHS_WARNINGS",
            f"`kast --output json developer inspect paths` reported warnings: {paths_json['warnings']}",
        )
    return ready_json if isinstance(ready_json, dict) else None


def resource_output_ready_issues(issues: Any) -> bool:
    if not isinstance(issues, list) or not issues:
        return False
    return all(is_resource_output_ready_issue(issue) for issue in issues)


def is_resource_output_ready_issue(issue: Any) -> bool:
    if not isinstance(issue, str):
        return False
    return " output checksum mismatch at " in issue or " output is missing: " in issue


def manifest_resources(ready_json: dict[str, Any] | None) -> list[dict[str, Any]]:
    install = ready_json.get("install") if isinstance(ready_json, dict) else None
    if not isinstance(install, dict):
        return []
    resources: list[dict[str, Any]] = []
    for repo in install.get("repos", []):
        if not isinstance(repo, dict):
            continue
        for resource in repo.get("resources", []):
            if isinstance(resource, dict):
                resource = dict(resource)
                resource["repoPath"] = repo.get("path")
                resources.append(resource)
    return resources


def resource_record_for_target(
    ready_json: dict[str, Any] | None,
    kind: str,
    target: Path,
) -> dict[str, Any] | None:
    target_value = normalize(target)
    for resource in manifest_resources(ready_json):
        resource_target = resource.get("targetPath")
        if not isinstance(resource_target, str):
            continue
        if resource.get("kind") == kind and normalize(Path(resource_target)) == target_value:
            return resource
    return None


def manifest_output_mismatches(resource: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not isinstance(resource, dict):
        return []
    mismatches: list[dict[str, Any]] = []
    for output in resource.get("outputChecksums", []):
        if not isinstance(output, dict):
            continue
        path_value = output.get("path")
        expected = output.get("sha256")
        if not isinstance(path_value, str) or not isinstance(expected, str):
            continue
        actual = file_sha256(Path(path_value))
        if actual != expected:
            mismatches.append(
                {
                    "path": path_value,
                    "expectedSha256": expected,
                    "actualSha256": actual,
                }
            )
    return mismatches


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
    expected_version: str | None,
    required: bool,
    ready_json: dict[str, Any] | None,
    kast_executable: str | None = None,
    target_dirs: list[Path] | None = None,
) -> None:
    target_dirs = target_dirs or [workspace_root / ".github"]
    targets = [
        copilot_target_check(github_dir, expected_version, ready_json)
        for github_dir in target_dirs
    ]
    check = dict(targets[0]) if targets else {"targets": []}
    check["targets"] = targets
    result["checks"]["copilotPackage"] = check
    stale_targets = [target for target in targets if not target["current"]]
    stale_installed_targets = [
        target
        for target in targets
        if target["exists"]
        and (
            target["manifestOutputMismatches"]
            or not target["versionMatchesExpected"]
            or not target["manifestResource"]
            or target["retiredMarkerExists"]
        )
    ]
    if required and stale_targets:
        recovery = copilot_recovery_command(kast_executable, workspace_root, stale_targets, target_dirs)
        add_issue(
            result,
            "COPILOT_PACKAGE_STALE",
            f"Repository Copilot package is missing or stale under {stale_targets[0]['target']}.",
            recovery,
        )
    elif stale_installed_targets:
        add_warning(
            result,
            "COPILOT_PACKAGE_STALE",
            f"Repository Copilot package differs from the manifest-backed expected state under {stale_installed_targets[0]['target']}.",
        )


def copilot_target_check(
    github_dir: Path,
    expected_version: str | None,
    ready_json: dict[str, Any] | None,
) -> dict[str, Any]:
    files = {
        relative: {
            "path": str(github_dir / relative),
            "exists": (github_dir / relative).is_file(),
        }
        for relative in COPILOT_FILES
    }
    retired_marker_exists = (github_dir / ".kast-copilot-version").exists()
    resource = resource_record_for_target(ready_json, "COPILOT_PACKAGE", github_dir)
    output_mismatches = manifest_output_mismatches(resource)
    missing = [relative for relative, info in files.items() if not info["exists"]]
    stale = bool(output_mismatches) if resource else False
    version_mismatch = expected_version and resource and expected_version != resource.get("primitiveVersion")
    missing_record = github_dir.is_dir() and resource is None
    retired_marker = retired_marker_exists
    current = not (missing or stale or version_mismatch or missing_record or retired_marker)
    return {
        "target": str(github_dir),
        "exists": github_dir.is_dir(),
        "current": current,
        "files": files,
        "retiredMarkerExists": retired_marker_exists,
        "expectedVersion": expected_version,
        "manifestResource": resource,
        "manifestOutputMismatches": output_mismatches,
        "versionMatchesExpected": bool(
            resource and expected_version and resource.get("primitiveVersion") == expected_version
        ),
    }


def copilot_recovery_command(
    kast_executable: str | None,
    workspace_root: Path,
    stale_targets: list[dict[str, Any]],
    target_dirs: list[Path],
) -> str:
    default_target = workspace_root / ".github"
    target = Path(stale_targets[0]["target"]) if stale_targets else default_target
    if target_dirs and normalize(target_dirs[0]) != normalize(default_target):
        return setup_recovery_command(kast_executable, "copilot", target)
    return setup_recovery_command(kast_executable, "copilot")


def resource_target_from_target_dir(target_dir: Path) -> Path:
    if target_dir.name == "kast":
        return target_dir
    return target_dir / "kast"


def resource_targets(
    workspace_root: Path,
    kind: str,
    target_dirs: list[Path] | None = None,
) -> list[Path]:
    targets = [
        workspace_root / ".agents" / kind / "kast",
        workspace_root / ".codex" / kind / "kast",
        workspace_root / ".github" / kind / "kast",
        workspace_root / ".claude" / kind / "kast",
    ]
    for target_dir in target_dirs or []:
        target = resource_target_from_target_dir(target_dir)
        if target not in targets:
            targets.append(target)
    return targets


def resource_recovery_command(
    kast_executable: str | None,
    workspace_root: Path,
    kind: str,
    stale_targets: list[dict[str, Any]],
    target_dirs: list[Path] | None = None,
) -> str:
    if stale_targets:
        target_dir = Path(stale_targets[0]["path"]).parent
    elif target_dirs:
        target_dir = target_dirs[0]
    else:
        target_dir = resource_targets(workspace_root, kind)[0].parent
    return setup_recovery_command(kast_executable, target_dir)


def verify_resource_install(
    result: dict[str, Any],
    workspace_root: Path,
    expected_version: str | None,
    kind: str,
    required: bool,
    source_root: Path | None = None,
    content_files: list[str] | None = None,
    ready_json: dict[str, Any] | None = None,
    kast_executable: str | None = None,
    target_dirs: list[Path] | None = None,
) -> None:
    targets = []
    manifest_kind = "SKILL" if kind == "skills" else "INSTRUCTIONS"
    for target in resource_targets(workspace_root, kind, target_dirs):
        resource = resource_record_for_target(ready_json, manifest_kind, target)
        output_mismatches = manifest_output_mismatches(resource)
        retired_marker_exists = (target / ".kast-version").exists()
        content_mismatches: list[str] = []
        if target.is_dir() and not resource and source_root and content_files:
            for relative in content_files:
                source_hash = file_sha256(source_root / relative)
                target_hash = file_sha256(target / relative)
                if source_hash and source_hash != target_hash:
                    content_mismatches.append(relative)
        targets.append(
            {
                "path": str(target),
                "exists": target.is_dir(),
                "retiredMarkerExists": retired_marker_exists,
                "expectedVersion": expected_version,
                "manifestResource": resource,
                "manifestOutputMismatches": output_mismatches,
                "versionMatchesExpected": bool(
                    resource and expected_version and resource.get("primitiveVersion") == expected_version
                ),
                "contentMismatches": content_mismatches,
            }
        )
    result["checks"][kind] = {"targets": targets}
    installed = [target for target in targets if target["exists"]]
    stale = [
        target
        for target in installed
        if (expected_version and not target["versionMatchesExpected"])
        or target["retiredMarkerExists"]
        or not target["manifestResource"]
        or target["manifestOutputMismatches"]
        or target["contentMismatches"]
    ]
    if required and (not installed or stale):
        recovery = resource_recovery_command(
            kast_executable,
            workspace_root,
            kind,
            stale,
            target_dirs,
        )
        add_issue(
            result,
            f"{kind.upper()}_STALE",
            f"No current repository-local Kast {kind} install was found.",
            recovery,
        )
    elif stale:
        recovery = resource_recovery_command(
            kast_executable,
            workspace_root,
            kind,
            stale,
            target_dirs,
        )
        add_warning(
            result,
            f"{kind.upper()}_STALE",
            f"A repository-local Kast {kind} install exists but does not match the current expected state. Recovery: {recovery}",
        )


def main() -> int:
    global RECOVERY
    args = parse_args()
    workspace_root = normalize(args.workspace_root)
    script_root = Path(__file__).resolve().parents[1]
    explicit_skill_root = normalize(args.skill_root) if args.skill_root else None
    skill_root = explicit_skill_root or script_root
    skill_target_dirs = [normalize(target_dir) for target_dir in args.skill_target_dir]

    which_kast = shutil.which("kast")
    if args.kast_bin:
        kast_bin = str(normalize(args.kast_bin))
    elif which_kast:
        kast_bin = which_kast
    else:
        kast_bin = None
    RECOVERY = recovery_commands(kast_bin)

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
        "skill": str(skill_root / "SKILL.md"),
        "skillExists": (skill_root / "SKILL.md").is_file(),
        "installedContent": ["SKILL.md"],
        "explicitOverride": explicit_skill_root is not None,
    }
    if not (skill_root / "SKILL.md").is_file():
        add_issue(result, "SOURCE_SKILL_MISSING", f"Missing skill entrypoint: {skill_root / 'SKILL.md'}", None)

    result["checks"]["binaryResolution"] = {
        "pathLookup": which_kast,
        "selected": kast_bin,
    }
    if not kast_bin:
        add_issue(result, "KAST_NOT_FOUND", "`kast` was not found on PATH.", RECOVERY["ready"])
        ready_json = None
    else:
        kast_command = [kast_bin]
        verify_command_surface(result, kast_command, workspace_root, args.timeout)
        ready_json = verify_ready_and_paths(
            result,
            kast_command,
            workspace_root,
            args.timeout,
            explicit_skill_root is not None,
        )

    expected_version = result["checks"].get("commandSurface", {}).get("cliVersion")
    verify_workspace(result, workspace_root, args.require_gradle_project)
    verify_resource_install(
        result,
        workspace_root,
        expected_version,
        "skills",
        args.require_skill,
        skill_root,
        ["SKILL.md"],
        ready_json,
        kast_bin,
        skill_target_dirs,
    )

    result["ok"] = not result["issues"]
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
