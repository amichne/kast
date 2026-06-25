#!/usr/bin/env python3
"""File-backed `kast agent call` harness for nontrivial requests."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
RECOVERABLE_BACKEND_CODES = {
    "NO_BACKEND_AVAILABLE",
    "INDEX_UNAVAILABLE",
    "METRICS_DB_UNAVAILABLE",
}
MUTATING_METHODS = {
    "raw/apply-edits",
    "raw/optimize-imports",
    "raw/rename",
    "symbol/rename",
    "symbol/write-and-validate",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a params file, run `kast agent call`, and emit file-backed JSON evidence."
    )
    parser.add_argument("method")
    inputs = parser.add_mutually_exclusive_group()
    inputs.add_argument("--params-file", type=Path)
    inputs.add_argument("--params-json")
    parser.add_argument("--workspace-root", type=Path, default=Path.cwd())
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--kast-bin", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--allow-mutation", action="store_true")
    parser.add_argument("--timeout", type=int, default=120)
    return parser.parse_args()


def normalize(path: Path) -> Path:
    return path.expanduser().resolve()


def default_catalog() -> Path:
    return Path(__file__).resolve().parents[1] / "references" / "commands.json"


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"invalid JSON in {path}: {error}") from error
    except OSError as error:
        raise SystemExit(f"could not read {path}: {error}") from error


def load_input(args: argparse.Namespace) -> Any:
    if args.params_file:
        return load_json(normalize(args.params_file))
    if args.params_json:
        try:
            return json.loads(args.params_json)
        except json.JSONDecodeError as error:
            raise SystemExit(f"invalid --params-json: {error}") from error
    if not sys.stdin.isatty():
        raw = sys.stdin.read()
        if raw.strip():
            try:
                return json.loads(raw)
            except json.JSONDecodeError as error:
                raise SystemExit(f"invalid JSON on stdin: {error}") from error
    return {}


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
            "exitCode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "timedOut": False,
        }
    except FileNotFoundError as error:
        return {
            "exitCode": 127,
            "stdout": "",
            "stderr": str(error),
            "timedOut": False,
        }
    except subprocess.TimeoutExpired as error:
        return {
            "exitCode": 124,
            "stdout": error.stdout or "",
            "stderr": error.stderr or "",
            "timedOut": True,
        }


def issue(code: str, message: str, recovery: str | None = None) -> dict[str, str]:
    item = {"code": code, "message": message}
    if recovery:
        item["recovery"] = recovery
    return item


def catalog_methods(catalog_path: Path) -> set[str]:
    catalog = load_json(catalog_path)
    commands = catalog.get("commands")
    if not isinstance(commands, dict):
        raise SystemExit(f"catalog {catalog_path} does not contain a commands object")
    return set(commands)


def envelope_summary(envelope: Any) -> dict[str, Any] | None:
    if not isinstance(envelope, dict):
        return None
    result = envelope.get("result")
    error = envelope.get("error")
    summary: dict[str, Any] = {
        "ok": envelope.get("ok"),
        "method": envelope.get("method"),
        "resultType": result.get("type") if isinstance(result, dict) else None,
        "nestedOk": result.get("ok") if isinstance(result, dict) and "ok" in result else None,
    }
    if error is not None:
        summary["error"] = error
    return summary


def recovery_from_text(text: str, workspace_root: Path) -> list[str]:
    commands = []
    if "unrecognized subcommand 'agent'" in text or "KAST_AGENT_UNAVAILABLE" in text:
        commands.append("./gradlew installDevelopmentLocal")
    if any(code in text for code in RECOVERABLE_BACKEND_CODES):
        commands.append(f"kast up --workspace-root {json.dumps(str(workspace_root))} --backend idea")
    if "INSTALL_MANIFEST" in text or "install manifest" in text:
        commands.append("kast doctor --repair")
    return commands


def main() -> int:
    args = parse_args()
    workspace_root = normalize(args.workspace_root)
    catalog_path = normalize(args.catalog) if args.catalog else default_catalog()
    methods = catalog_methods(catalog_path)
    out_dir = normalize(args.out_dir) if args.out_dir else Path(tempfile.mkdtemp(prefix="kast-agent-call-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    params_file = out_dir / "params.json"
    stdout_file = out_dir / "stdout.json"
    stderr_file = out_dir / "stderr.txt"
    input_value = load_input(args)
    params_file.write_text(json.dumps(input_value, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    kast_lookup = shutil.which("kast")
    kast_bin = str(normalize(args.kast_bin)) if args.kast_bin else kast_lookup or "kast"
    command = [
        kast_bin,
        "agent",
        "call",
        args.method,
        "--params-file",
        str(params_file),
        "--workspace-root",
        str(workspace_root),
    ]

    result: dict[str, Any] = {
        "type": "KAST_AGENT_CALL",
        "schemaVersion": SCHEMA_VERSION,
        "ok": False,
        "method": args.method,
        "workspaceRoot": str(workspace_root),
        "catalog": str(catalog_path),
        "dryRun": args.dry_run,
        "command": command,
        "files": {
            "params": str(params_file),
            "stdout": str(stdout_file),
            "stderr": str(stderr_file),
        },
        "issues": [],
        "recovery": [],
    }

    if args.method not in methods:
        result["issues"].append(
            issue(
                "UNKNOWN_METHOD",
                f"{args.method} is not present in {catalog_path}.",
                "inspect references/commands.json",
            )
        )
    if args.method in MUTATING_METHODS and not args.allow_mutation and not args.dry_run:
        result["issues"].append(
            issue(
                "MUTATION_NOT_ALLOWED",
                f"{args.method} can modify source. Re-run with --allow-mutation after confirming the edit is intended.",
            )
        )
    if not workspace_root.is_dir():
        result["issues"].append(issue("WORKSPACE_ROOT_MISSING", f"{workspace_root} is not a directory."))
    if result["issues"]:
        json.dump(result, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 1

    if args.dry_run:
        result["ok"] = True
        json.dump(result, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 0

    agent_help = command_record([kast_bin, "agent", "--help"], workspace_root, args.timeout)
    if agent_help["exitCode"] != 0:
        stderr_file.write_text(agent_help["stderr"], encoding="utf-8")
        result["issues"].append(
            issue(
                "KAST_AGENT_UNAVAILABLE",
                "`kast agent --help` failed; the installed skill and active binary are incompatible. Upgrade or reinstall Kast.",
                "./gradlew installDevelopmentLocal",
            )
        )
        result["process"] = {"exitCode": agent_help["exitCode"], "preflight": "agent --help"}
        result["recovery"] = ["./gradlew installDevelopmentLocal"]
        json.dump(result, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 1

    completed = command_record(command, workspace_root, args.timeout)
    stdout_file.write_text(completed["stdout"], encoding="utf-8")
    stderr_file.write_text(completed["stderr"], encoding="utf-8")
    result["process"] = {
        "exitCode": completed["exitCode"],
        "timedOut": completed["timedOut"],
    }

    envelope = None
    try:
        envelope = json.loads(completed["stdout"])
    except json.JSONDecodeError:
        result["issues"].append(issue("AGENT_OUTPUT_INVALID", "`kast agent` stdout was not JSON."))
    result["envelope"] = envelope_summary(envelope)

    text = completed["stdout"] + "\n" + completed["stderr"]
    result["recovery"] = recovery_from_text(text, workspace_root)
    if completed["exitCode"] != 0:
        result["issues"].append(issue("KAST_AGENT_FAILED", "`kast agent call` exited non-zero."))
    if isinstance(envelope, dict) and envelope.get("ok") is False:
        result["issues"].append(issue("KAST_AGENT_ENVELOPE_FAILED", "`kast agent` returned ok=false."))
    nested = envelope.get("result") if isinstance(envelope, dict) else None
    if isinstance(nested, dict) and nested.get("ok") is False:
        result["issues"].append(issue("KAST_RESULT_FAILED", "`kast agent` result returned ok=false."))

    result["ok"] = not result["issues"]
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
