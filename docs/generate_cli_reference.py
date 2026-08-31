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
    hosted_exposure: str
    hosted_intents: tuple[str, ...]


@dataclass(frozen=True)
class OperationMetadata:
    enum_name: str
    operation_id: str
    hosted_exposure: str
    hosted_intents: tuple[str, ...]


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
    "start": "Admit the existing exact-root IDE endpoint and return workspace readiness.",
    "stop": "Reject because the already-running IDE owns endpoint lifecycle.",
    "status": "Report running after compatible exact-root endpoint admission.",
    "clean": "Reject because active IDE-hosted state is not owned by the CLI.",
    "reindex": "Reject because the CLI cannot stop or rebuild IDE-owned semantic state.",
}

LOCAL_COMMAND_DESCRIPTIONS = {
    "product inspect": (
        "Report installed control identity plus direct root and IDE endpoint evidence without "
        "requiring compatible runtime admission."
    ),
}


def parse_operations(registry_path: Path) -> list[OperationMetadata]:
    if not registry_path.is_file():
        raise ValueError(
            "generated operation registry is missing; run "
            "./gradlew :protocol:wire:generateOperationRegistry"
        )
    registry = json.loads(registry_path.read_text())
    if registry.get("schemaVersion") != 2:
        raise ValueError("generated operation registry has an unsupported schema version")
    rows = registry.get("operations")
    if not isinstance(rows, list):
        raise ValueError("generated operation registry has no operation metadata")
    operations: list[OperationMetadata] = []
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("generated operation registry has a non-object operation")
        operation_id = row.get("operationId")
        exposure = row.get("hostedExposure")
        intents = row.get("intents")
        if not isinstance(operation_id, str) or not operation_id:
            raise ValueError("generated operation registry has an invalid operation identity")
        if exposure not in {"public", "internal_only", "unavailable"}:
            raise ValueError(f"invalid hosted exposure for {operation_id}: {exposure!r}")
        if not isinstance(intents, list) or not all(
            isinstance(intent, str) and intent for intent in intents
        ):
            raise ValueError(f"invalid hosted intents for {operation_id}: {intents!r}")
        operations.append(
            OperationMetadata(
                operation_id.upper().replace(".", "_"),
                operation_id,
                exposure,
                tuple(intents),
            )
        )
    operation_ids = [operation.operation_id for operation in operations]
    if len(set(operation_ids)) != len(operation_ids):
        raise ValueError("canonical operation identities are not unique")
    if set(operation_ids) != set(OPERATION_DESCRIPTIONS):
        raise ValueError("operation descriptions do not match the generated registry")
    return operations


def kotlin_string(expression: str) -> str:
    literals = re.findall(r'"(?:[^"\\]|\\.)*"', expression)
    if not literals:
        raise ValueError(f"schemaUsage has no string literals: {expression!r}")
    return "".join(ast.literal_eval(literal) for literal in literals)


def parse_semantic_commands(
    root: Path,
    operations: list[OperationMetadata],
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

    expected_enums = {operation.enum_name for operation in operations}
    if set(by_enum) != expected_enums:
        missing = sorted(expected_enums - set(by_enum))
        extra = sorted(set(by_enum) - expected_enums)
        raise ValueError(f"CLI projection mismatch; missing={missing}, extra={extra}")

    return [
        SemanticCommand(
            operation.operation_id,
            by_enum[operation.enum_name],
            operation.hosted_exposure,
            operation.hosted_intents,
        )
        for operation in operations
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
    match = re.search(
        r"enum class CliLocalMetadataCommand\s*\{([^}]+)\}",
        source.read_text(),
    )
    if match is None:
        raise ValueError("CliLocalMetadataCommand could not be read")
    names = [name.strip() for name in match.group(1).split(",") if name.strip()]
    flags = ["--help"] + [f"--{name.lower()}" for name in names]
    if flags != ["--help", "--version", "--schema"]:
        raise ValueError(f"local flag set changed: {flags}")
    return flags


def parse_local_commands(root: Path) -> list[str]:
    source = root / (
        "cli/src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandModel.kt"
    )
    match = re.search(
        r"enum class CliProductCommand\([^)]*\)\s*\{(?P<body>.*?)\n\}",
        source.read_text(),
        re.DOTALL,
    )
    if match is None:
        raise ValueError("CliProductCommand could not be read")
    commands = re.findall(
        r'^[ ]*[A-Z_]+\("([a-z ]+)"\),$',
        match.group("body"),
        re.MULTILINE,
    )
    if set(commands) != set(LOCAL_COMMAND_DESCRIPTIONS):
        raise ValueError(f"local command set changed: {commands}")
    return commands


def table_cell(value: str) -> str:
    return value.replace("|", "\\|")


def hosted_description(command: SemanticCommand) -> str:
    description = OPERATION_DESCRIPTIONS[command.operation_id]
    if not command.hosted_intents:
        return description
    intents = ", ".join(f"`{intent}`" for intent in command.hosted_intents)
    return f"Hosted only for {intents}. {description}"


def render(
    semantic: list[SemanticCommand],
    lifecycle: list[str],
    local_commands: list[str],
    local_flags: list[str],
) -> str:
    hosted_rows = "\n".join(
        f"| `{command.operation_id}` | `kast {table_cell(command.usage)}` | "
        f"{hosted_description(command)} |"
        for command in semantic
        if command.hosted_exposure == "public"
    )
    deferred_rows = "\n".join(
        f"| `{command.operation_id}` | `kast {table_cell(command.usage)}` | "
        "Available only as an internal hosted service; no direct endpoint route. |"
        for command in semantic
        if command.hosted_exposure != "public"
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
    local_command_rows = "\n".join(
        f"| `kast {command}` | {LOCAL_COMMAND_DESCRIPTIONS[command]} |"
        for command in local_commands
    )
    return f"""---
title: "CLI reference"
description: "Canonical Kast command shapes generated from the typed operation registry and Kotlin command graph."
icon: "terminal"
keywords: ["CLI", "commands", "schema", "operations"]
---

{{/* Generated by docs/generate_cli_reference.py. Do not edit. */}}

This page is generated from the same typed operation registry and Kotlin
command graph used by `kast --schema`. The documentation check fails when this
page differs from either authority.

Run `kast --schema` when a tool needs the contract as JSON. Run a command with
`--help` when you need every option and intent-specific combination.

## Installed server projection

The schema's `serverProjection` is generated with the installed command graph.
It owns server-visible tool names and descriptions, closed input and output
JSON Schemas, deferred-loading policy, and field-to-CLI option bindings. It
contains every operation marked `public` by the hosted registry and excludes
internal-only operations. A broker must qualify and consume that projection
from its exact configured `kast` executable; it must not infer tool shapes from
the executable's version or from the human-readable command strings.

## Hosted endpoint operations

Run semantic commands from the repository root. Each command emits one JSON
document on standard output. A rejected command emits one diagnostic document
on standard error. The installed IDE plugin publishes the ten operations marked
`public` by the generated operation registry:

| Operation | Command shape | Result role |
| --- | --- | --- |
{hosted_rows}

## Canonical operations without a direct hosted route

<Warning>
  These operations remain in the canonical registry and command graph because
  hosted topology, traversal, planning, and verification consume them
  internally. The installed IDE endpoint does not publish them as direct
  routes.
</Warning>

| Operation | Command shape | Current availability |
| --- | --- | --- |
{deferred_rows}

## Runtime lifecycle

The IDE owns this lifecycle. Every lifecycle command first admits the
already-running compatible endpoint for the exact current root.

| Command | Effect |
| --- | --- |
{lifecycle_rows}

## Process-local inspection

These commands inspect installed control or endpoint evidence directly. They do
not require successful hosted runtime admission.

| Command | Result |
| --- | --- |
{local_command_rows}

## Process-local flags

These flags do not contact the hosted IDE endpoint.

| Flag | Result |
| --- | --- |
{local_rows}

For a first run, continue with [Set up and start Kast](/start). For the
meaning of successful and limited answers, read
[Trust the evidence](/concepts/evidence-boundaries).
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--root", type=Path)
    parser.add_argument("--registry", type=Path)
    args = parser.parse_args()

    root = (args.root or Path(__file__).resolve().parents[1]).resolve()
    target = root / "docs/public/reference/cli.mdx"
    registry = args.registry or (
        root / "protocol/wire/build/generated/operation-registry/operation-registry.json"
    )
    operations = parse_operations(registry)
    semantic = parse_semantic_commands(root, operations)
    lifecycle = parse_lifecycle_commands(root)
    local_commands = parse_local_commands(root)
    local_flags = parse_local_flags(root)
    rendered = render(semantic, lifecycle, local_commands, local_flags)

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
