#!/usr/bin/env python3
"""Generate the public CLI reference from the canonical Kotlin command graph."""

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SemanticCommand:
    operation_id: str
    usage: str


OPERATION_DESCRIPTIONS = {
    "workspace.inspect": "Report exact-root readiness and workspace identity.",
    "topology.build": "Build or reuse the durable graph and return its generation and digest.",
    "symbol.discover": "Find bounded candidates by name, location, structure, or text.",
    "symbol.resolve": "Refine one candidate into an exact symbol selector.",
    "symbol.describe": "Describe one exact, current-generation symbol.",
    "relation.read": "Read one bounded semantic relation from an exact symbol.",
    "traversal.run": "Read the eligible durable snapshot with explicit depth and result limits.",
    "diagnostic.check": "Read compiler diagnostics for one explicit scope.",
    "change.plan": "Derive a typed plan without writing the workspace.",
    "change.apply": "Apply one admitted plan and return an application identity.",
    "change.verify": "Check one application against resulting semantic evidence.",
    "change.recover": "Restore one plan to a known workspace state.",
}

LIFECYCLE_DESCRIPTIONS = {
    "start": "Start or reuse the exact-root runtime and wait for semantic readiness.",
    "stop": "Stop the exact-root runtime and retire its endpoint markers.",
    "status": "Read the exact-root runtime state.",
    "clean": "Remove state and markers for a stopped runtime.",
    "reindex": "Stop, clean, and rebuild exact-root semantic state.",
}


def parse_operations(registry_path: Path) -> list[tuple[str, str]]:
    if not registry_path.is_file():
        raise ValueError(
            "generated operation registry is missing; run "
            "./gradlew :protocol:wire:generateOperationRegistry"
        )
    registry = json.loads(registry_path.read_text())
    if registry.get("schemaVersion") != 1:
        raise ValueError("generated operation registry has an unsupported schema version")
    operation_ids = registry.get("operationIds")
    if not isinstance(operation_ids, list) or not all(
        isinstance(operation_id, str) and operation_id for operation_id in operation_ids
    ):
        raise ValueError("generated operation registry has invalid operation identities")
    if len(set(operation_ids)) != len(operation_ids):
        raise ValueError("canonical operation identities are not unique")
    if set(operation_ids) != set(OPERATION_DESCRIPTIONS):
        raise ValueError("operation descriptions do not match the generated registry")
    return [
        (operation_id.upper().replace(".", "_"), operation_id)
        for operation_id in operation_ids
    ]


def kotlin_string(expression: str) -> str:
    literals = re.findall(r'"(?:[^"\\]|\\.)*"', expression)
    if not literals:
        raise ValueError(f"schemaUsage has no string literals: {expression!r}")
    return "".join(ast.literal_eval(literal) for literal in literals)


def parse_semantic_commands(
    root: Path,
    operations: list[tuple[str, str]],
) -> list[SemanticCommand]:
    command_root = root / "cli/src/main/kotlin/io/github/amichne/kast/cli/command"
    by_enum: dict[str, str] = {}
    pattern = re.compile(
        r"operation\s*=\s*CanonicalOperation\.(?P<operation>[A-Z_]+),"
        r"\s*schemaUsage\s*=\s*(?P<usage>.*?),\s*preparer\s*=",
        re.DOTALL,
    )
    for source in sorted(command_root.rglob("*Commands.kt")):
        for match in pattern.finditer(source.read_text()):
            operation = match.group("operation")
            if operation in by_enum:
                raise ValueError(f"duplicate CLI projection for {operation}")
            by_enum[operation] = kotlin_string(match.group("usage"))

    expected_enums = {enum_name for enum_name, _ in operations}
    if set(by_enum) != expected_enums:
        missing = sorted(expected_enums - set(by_enum))
        extra = sorted(set(by_enum) - expected_enums)
        raise ValueError(f"CLI projection mismatch; missing={missing}, extra={extra}")

    return [
        SemanticCommand(operation_id, by_enum[enum_name])
        for enum_name, operation_id in operations
    ]


def parse_lifecycle_commands(root: Path) -> list[str]:
    source = root / (
        "cli/src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandModel.kt"
    )
    match = re.search(
        r"enum class CliLifecycleCommand\([^)]*\)\s*\{(?P<body>.*?)\n\}",
        source.read_text(),
        re.DOTALL,
    )
    if match is None:
        raise ValueError("CliLifecycleCommand could not be read")
    commands = re.findall(r'^[ ]*[A-Z_]+\("([a-z-]+)"\),$', match.group("body"), re.MULTILINE)
    if set(commands) != set(LIFECYCLE_DESCRIPTIONS):
        raise ValueError(f"lifecycle command set changed: {commands}")
    return commands


def parse_local_flags(root: Path) -> list[str]:
    source = root / (
        "cli/src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandModel.kt"
    )
    match = re.search(r"enum class CliLocalCommand\s*\{([^}]+)\}", source.read_text())
    if match is None:
        raise ValueError("CliLocalCommand could not be read")
    names = [name.strip() for name in match.group(1).split(",") if name.strip()]
    flags = ["--help"] + [f"--{name.lower()}" for name in names]
    if flags != ["--help", "--version", "--schema"]:
        raise ValueError(f"local flag set changed: {flags}")
    return flags


def table_cell(value: str) -> str:
    return value.replace("|", "\\|")


def render(
    semantic: list[SemanticCommand],
    lifecycle: list[str],
    local_flags: list[str],
) -> str:
    semantic_rows = "\n".join(
        f"| `{command.operation_id}` | `kast {table_cell(command.usage)}` | "
        f"{OPERATION_DESCRIPTIONS[command.operation_id]} |"
        for command in semantic
    )
    lifecycle_rows = "\n".join(
        f"| `kast {command}` | {LIFECYCLE_DESCRIPTIONS[command]} |"
        for command in lifecycle
    )
    local_rows = "\n".join(
        {
            "--help": "| `kast --help` | Show the local command graph. |",
            "--version": "| `kast --version` | Show the installed control version. |",
            "--schema": "| `kast --schema` | Emit the machine-readable public contract. |",
        }[flag]
        for flag in local_flags
    )
    return f"""<!-- Generated by docs/generate_cli_reference.py. Do not edit. -->

# CLI reference

This page is generated from the same typed operation registry and Kotlin
command graph used by `kast --schema`. The documentation check fails when this
page differs from either authority.

Run `kast --schema` when a tool needs the contract as JSON. Run a command with
`--help` when you need every option and intent-specific combination.

## Semantic operations

Run semantic commands from the repository root. Each command emits one JSON
document on standard output. A rejected command emits one diagnostic document
on standard error.

| Operation | Command shape | Result role |
| --- | --- | --- |
{semantic_rows}

## Runtime lifecycle

Lifecycle commands act on the runtime associated with the exact current root.

| Command | Effect |
| --- | --- |
{lifecycle_rows}

## Process-local flags

These flags do not contact the hosted IDE endpoint.

| Flag | Result |
| --- | --- |
{local_rows}

For a first run, continue with [Set up and start Kast](../start.md). For the
meaning of successful and limited answers, read
[Trust the evidence](../concepts/evidence-boundaries.md).
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--root", type=Path)
    parser.add_argument("--registry", type=Path)
    args = parser.parse_args()

    root = (args.root or Path(__file__).resolve().parents[1]).resolve()
    target = root / "docs/public/reference/cli.md"
    registry = args.registry or (
        root / "protocol/wire/build/generated/operation-registry/operation-registry.json"
    )
    operations = parse_operations(registry)
    semantic = parse_semantic_commands(root, operations)
    lifecycle = parse_lifecycle_commands(root)
    local_flags = parse_local_flags(root)
    rendered = render(semantic, lifecycle, local_flags)

    if args.check:
        if not target.is_file() or target.read_text() != rendered:
            print("cli-reference: generated page is missing or stale", file=sys.stderr)
            return 1
        print("cli-reference: generated page is current")
        return 0

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(rendered)
    print(f"cli-reference: wrote {target.relative_to(root)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
