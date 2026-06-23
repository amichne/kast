#!/usr/bin/env python3
"""Identity-first Kast semantic workflow runner."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
SCRIPT_DIR = Path(__file__).resolve().parent
CALL_HARNESS = SCRIPT_DIR / "kast-agent-call.py"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run compact Kast semantic workflows with file-backed evidence."
    )
    parser.add_argument("--workspace-root", type=Path, default=Path.cwd())
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--kast-bin", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--timeout", type=int, default=120)
    subcommands = parser.add_subparsers(dest="workflow", required=True)

    symbol = subcommands.add_parser("symbol", help="Query, resolve, and optionally gather references/callers.")
    symbol.add_argument("--symbol", required=True)
    symbol.add_argument("--kind", choices=["class", "interface", "object", "function", "property"])
    symbol.add_argument("--file-hint")
    symbol.add_argument("--containing-type")
    symbol.add_argument("--query-limit", type=int, default=10)
    symbol.add_argument("--references", action="store_true")
    symbol.add_argument("--include-declaration", action="store_true")
    symbol.add_argument("--callers", choices=["incoming", "outgoing"])
    symbol.add_argument("--caller-depth", type=int, default=3)

    diagnostics = subcommands.add_parser("diagnostics", help="Refresh touched files and run diagnostics.")
    diagnostics.add_argument("--file-path", action="append", required=True)
    diagnostics.add_argument("--skip-refresh", action="store_true")

    rename = subcommands.add_parser("rename", help="Build or apply a safe symbol rename request.")
    rename.add_argument("--new-name", required=True)
    rename.add_argument("--symbol")
    rename.add_argument("--kind", choices=["class", "interface", "object", "function", "property"])
    rename.add_argument("--file-hint")
    rename.add_argument("--containing-type")
    rename.add_argument("--file-path")
    rename.add_argument("--offset", type=int)
    rename.add_argument("--allow-mutation", action="store_true")

    write = subcommands.add_parser("write", help="Build or apply symbol/write-and-validate.")
    write.add_argument("--mode", choices=["create", "insert", "replace"], required=True)
    write.add_argument("--file-path", required=True)
    write.add_argument("--offset", type=int)
    write.add_argument("--start-offset", type=int)
    write.add_argument("--end-offset", type=int)
    content = write.add_mutually_exclusive_group()
    content.add_argument("--content")
    content.add_argument("--content-file")
    write.add_argument("--allow-mutation", action="store_true")

    return parser.parse_args()


def normalize(path: Path) -> Path:
    return path.expanduser().resolve()


def fail(message: str) -> None:
    raise SystemExit(message)


def require_absolute(path: str, field: str) -> str:
    candidate = Path(path).expanduser()
    if not candidate.is_absolute():
        fail(f"{field} must resolve to an absolute path: {path}")
    return str(candidate.resolve())


def optional(value: Any) -> bool:
    return value is not None and value != [] and value != ""


def drop_empty(params: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in params.items() if optional(value)}


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_call(
    method: str,
    params: dict[str, Any],
    args: argparse.Namespace,
    out_root: Path,
    step_name: str,
    allow_mutation: bool = False,
) -> dict[str, Any]:
    step_dir = out_root / step_name
    params_file = step_dir / "input.json"
    write_json(params_file, params)
    command = [
        sys.executable,
        str(CALL_HARNESS),
        method,
        "--params-file",
        str(params_file),
        "--workspace-root",
        str(normalize(args.workspace_root)),
        "--out-dir",
        str(step_dir),
        "--timeout",
        str(args.timeout),
    ]
    if args.kast_bin:
        command.extend(["--kast-bin", str(normalize(args.kast_bin))])
    if args.dry_run:
        command.append("--dry-run")
    if allow_mutation:
        command.append("--allow-mutation")
    completed = subprocess.run(command, text=True, capture_output=True, check=False)
    workflow_summary = step_dir / "workflow-summary.json"
    workflow_summary.write_text(completed.stdout, encoding="utf-8")
    summary: Any = None
    try:
        summary = json.loads(completed.stdout)
    except json.JSONDecodeError:
        summary = {
            "ok": False,
            "issues": [
                {
                    "code": "WORKFLOW_STEP_OUTPUT_INVALID",
                    "message": "kast-agent-call.py did not emit JSON.",
                }
            ],
        }
    return {
        "name": step_name,
        "method": method,
        "paramsFile": str(params_file),
        "exitCode": completed.returncode,
        "stdout": str(workflow_summary),
        "stderr": completed.stderr,
        "summary": summary,
    }


def symbol_steps(args: argparse.Namespace) -> list[tuple[str, str, dict[str, Any], bool]]:
    filters = {}
    query_params = {
        "query": args.symbol,
        "modes": ["exact", "lexical"],
        "filters": filters,
        "limit": args.query_limit,
        "includeEvidence": True,
        "includeNextRequests": True,
    }
    resolve_params = drop_empty(
        {
            "symbol": args.symbol,
            "kind": args.kind,
            "fileHint": args.file_hint,
            "containingType": args.containing_type,
            "includeDeclarationScope": True,
            "includeDocumentation": True,
            "surroundingLines": 3,
            "includeSurroundingMembers": True,
        }
    )
    steps = [
        ("symbol-query", "symbol/query", query_params, False),
        ("symbol-resolve", "symbol/resolve", resolve_params, False),
    ]
    if args.references:
        steps.append(
            (
                "symbol-references",
                "symbol/references",
                drop_empty(
                    {
                        "symbol": args.symbol,
                        "kind": args.kind,
                        "fileHint": args.file_hint,
                        "containingType": args.containing_type,
                        "includeDeclaration": args.include_declaration,
                    }
                ),
                False,
            )
        )
    if args.callers:
        steps.append(
            (
                "symbol-callers",
                "symbol/callers",
                drop_empty(
                    {
                        "symbol": args.symbol,
                        "kind": args.kind,
                        "fileHint": args.file_hint,
                        "containingType": args.containing_type,
                        "direction": args.callers,
                        "depth": args.caller_depth,
                    }
                ),
                False,
            )
        )
    return steps


def diagnostics_steps(args: argparse.Namespace) -> list[tuple[str, str, dict[str, Any], bool]]:
    file_paths = [require_absolute(path, "--file-path") for path in args.file_path]
    steps: list[tuple[str, str, dict[str, Any], bool]] = []
    if not args.skip_refresh:
        steps.append(("workspace-refresh", "raw/workspace-refresh", {"filePaths": file_paths}, False))
    steps.append(("diagnostics", "raw/diagnostics", {"filePaths": file_paths}, False))
    return steps


def rename_steps(args: argparse.Namespace) -> list[tuple[str, str, dict[str, Any], bool]]:
    if args.symbol:
        params = drop_empty(
            {
                "type": "RENAME_BY_SYMBOL_REQUEST",
                "symbol": args.symbol,
                "newName": args.new_name,
                "kind": args.kind,
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
            }
        )
    else:
        if not args.file_path or args.offset is None:
            fail("rename requires either --symbol or both --file-path and --offset")
        params = {
            "type": "RENAME_BY_OFFSET_REQUEST",
            "filePath": require_absolute(args.file_path, "--file-path"),
            "offset": args.offset,
            "newName": args.new_name,
        }
    return [("rename", "symbol/rename", params, args.allow_mutation)]


def write_steps(args: argparse.Namespace) -> list[tuple[str, str, dict[str, Any], bool]]:
    params: dict[str, Any] = {
        "filePath": require_absolute(args.file_path, "--file-path"),
    }
    if args.content is not None:
        params["content"] = args.content
    if args.content_file is not None:
        params["contentFile"] = require_absolute(args.content_file, "--content-file")
    if args.mode == "create":
        params["type"] = "CREATE_FILE_REQUEST"
    elif args.mode == "insert":
        if args.offset is None:
            fail("write --mode insert requires --offset")
        params["type"] = "INSERT_AT_OFFSET_REQUEST"
        params["offset"] = args.offset
    else:
        if args.start_offset is None or args.end_offset is None:
            fail("write --mode replace requires --start-offset and --end-offset")
        if args.end_offset < args.start_offset:
            fail("--end-offset must be greater than or equal to --start-offset")
        params["type"] = "REPLACE_RANGE_REQUEST"
        params["startOffset"] = args.start_offset
        params["endOffset"] = args.end_offset
    return [("write-and-validate", "symbol/write-and-validate", params, args.allow_mutation)]


def workflow_steps(args: argparse.Namespace) -> list[tuple[str, str, dict[str, Any], bool]]:
    if args.workflow == "symbol":
        return symbol_steps(args)
    if args.workflow == "diagnostics":
        return diagnostics_steps(args)
    if args.workflow == "rename":
        return rename_steps(args)
    if args.workflow == "write":
        return write_steps(args)
    fail(f"unsupported workflow: {args.workflow}")


def main() -> int:
    args = parse_args()
    out_root = normalize(args.out_dir) if args.out_dir else Path(tempfile.mkdtemp(prefix="kast-semantic-workflow-"))
    out_root.mkdir(parents=True, exist_ok=True)
    steps = []
    issues = []
    for step_name, method, params, allow_mutation in workflow_steps(args):
        step = run_call(method, params, args, out_root, step_name, allow_mutation)
        steps.append(step)
        summary = step["summary"]
        if step["exitCode"] != 0 or not isinstance(summary, dict) or not summary.get("ok", False):
            issues.append(
                {
                    "code": "WORKFLOW_STEP_FAILED",
                    "message": f"{step_name} failed",
                    "step": step_name,
                }
            )
            if not args.dry_run:
                break
    result = {
        "type": "KAST_SEMANTIC_WORKFLOW",
        "schemaVersion": SCHEMA_VERSION,
        "ok": not issues,
        "workflow": args.workflow,
        "workspaceRoot": str(normalize(args.workspace_root)),
        "outDir": str(out_root),
        "dryRun": args.dry_run,
        "steps": steps,
        "issues": issues,
    }
    write_json(out_root / "workflow.json", result)
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
