#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_json(path: str) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path: str, value: object) -> None:
    Path(path).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def command_extract_apply_request(args: argparse.Namespace) -> int:
    plan = load_json(args.plan_file)
    write_json(
        args.out_file,
        {
            "edits": plan.get("edits", []),
            "fileHashes": plan.get("fileHashes", []),
        },
    )
    return 0


def iter_affected_files(plan: dict) -> list[str]:
    affected = plan.get("affectedFiles")
    if isinstance(affected, list) and affected:
        return [str(item) for item in affected]

    ordered: list[str] = []
    seen: set[str] = set()
    for section in ("edits", "fileHashes"):
        for item in plan.get(section, []):
            if not isinstance(item, dict):
                continue
            file_path = item.get("filePath")
            if not file_path or file_path in seen:
                continue
            seen.add(file_path)
            ordered.append(str(file_path))
    return ordered


def command_affected_files_csv(args: argparse.Namespace) -> int:
    plan = load_json(args.plan_file)
    print(",".join(iter_affected_files(plan)))
    return 0


def command_affected_files_list(args: argparse.Namespace) -> int:
    plan = load_json(args.plan_file)
    for file_path in iter_affected_files(plan):
        print(file_path)
    return 0


def command_count_edits(args: argparse.Namespace) -> int:
    plan = load_json(args.plan_file)
    print(len(plan.get("edits", [])))
    return 0


def command_check_diagnostics(args: argparse.Namespace) -> int:
    diagnostics_result = load_json(args.diagnostics_file)
    diagnostics = diagnostics_result.get("diagnostics", [])
    errors = [
        diagnostic
        for diagnostic in diagnostics
        if isinstance(diagnostic, dict) and diagnostic.get("severity") == "ERROR"
    ]

    print(len(errors))
    if not errors:
        return 0

    for diagnostic in errors:
        location = diagnostic.get("location") or {}
        file_path = location.get("filePath", "<unknown>")
        line = location.get("startLine", "?")
        column = location.get("startColumn", "?")
        message = diagnostic.get("message", "<no message>")
        print(f"{file_path}:{line}:{column}: {message}", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Utilities for kast rename plans and diagnostics results.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    extract = subparsers.add_parser("extract-apply-request")
    extract.add_argument("plan_file")
    extract.add_argument("out_file")
    extract.set_defaults(func=command_extract_apply_request)

    csv_parser = subparsers.add_parser("affected-files-csv")
    csv_parser.add_argument("plan_file")
    csv_parser.set_defaults(func=command_affected_files_csv)

    list_parser = subparsers.add_parser("affected-files-list")
    list_parser.add_argument("plan_file")
    list_parser.set_defaults(func=command_affected_files_list)

    count_parser = subparsers.add_parser("count-edits")
    count_parser.add_argument("plan_file")
    count_parser.set_defaults(func=command_count_edits)

    diagnostics_parser = subparsers.add_parser("check-diagnostics")
    diagnostics_parser.add_argument("diagnostics_file")
    diagnostics_parser.set_defaults(func=command_check_diagnostics)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
