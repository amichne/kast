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
    "index.sync": "Refresh admitted source roots, await indexing, and publish current semantic evidence.",
    "topology.build": "Build or reuse the durable graph and return its generation and digest.",
    "symbol.discover": "Find bounded candidates by name, location, structure, or text.",
    "symbol.inspect": "Refine a candidate or inspect an exact selector and return exact compiler evidence.",
    "source.read": "Read bounded source text and structure without establishing compiler identity.",
    "relation.read": "Read one bounded semantic relation from an exact symbol.",
    "traversal.run": (
        "Read one exact-root semantic generation as normalized node, edge, and proof tables "
        "with explicit depth and result limits."
    ),
    "diagnostic.check": "Read compiler diagnostics for one explicit scope.",
    "change.plan": "Derive a typed plan without writing the workspace.",
    "change.apply": "Apply one admitted plan, publish its successor, verify it, and return a receipt.",
    "change.recover": "Restore one plan to a known workspace state.",
}

LIFECYCLE_DESCRIPTIONS = {
    "start": "Start or reuse the exact-root private sidecar and return workspace readiness.",
    "stop": "Stop only the process proven to own the exact-root sidecar endpoint.",
    "status": "Passively report exact-root runtime identity and private cache state.",
}

LOCAL_COMMAND_DESCRIPTIONS = {
    "product inspect": (
        "Report installed sidecar identity plus direct root, Kast-cache, and default trace "
        "destination evidence without starting or admitting a runtime."
    ),
    "broker serve": (
        "Host the optional read-only preview Kotlin/Ktor Codex tool broker. Semantic CLI commands "
        "start the sidecar independently and do not require Codex."
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
    return f"Sidecar route is available only for {intents}. {description}"


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
        "Available only as an internal sidecar service; no direct endpoint route. |"
        for command in semantic
        if command.hosted_exposure != "public"
    )
    deferred_section = ""
    if deferred_rows:
        deferred_section = f"""
## Canonical operations without a direct sidecar route

<Warning>
  These operations remain in the canonical registry and command graph but the
  installed sidecar endpoint does not publish them as direct routes.
</Warning>

| Operation | Command shape | Current availability |
| --- | --- | --- |
{deferred_rows}
"""
    public_count = sum(command.hosted_exposure == "public" for command in semantic)
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
type: "CLI Reference"
title: "CLI reference"
description: "Canonical Kast command shapes generated from the typed operation registry and Kotlin command graph."
resource: "file://docs/public/reference/cli.mdx"
tags: ["CLI", "operations", "schema", "lifecycle"]
timestamp: "2026-09-03T00:00:00-04:00"
code_sources:
  - path: "cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt"
    lines: "24-330"
    symbols: ["InstalledServerProjectionDocument", "installedServerProjection"]
  - path: "cli/src/main/kotlin/io/github/amichne/kast/cli/broker/provider/KastProvider.kt"
    lines: "185-329"
    symbols: ["admitProjection"]
  - path: "cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/IndexSeedProtocol.kt"
    lines: "37-105"
    symbols: ["SupportedIdeRuntimePair"]
  - path: "protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt"
    lines: "7-20"
    symbols: ["CanonicalOperation"]
  - path: "cli/src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandModel.kt"
    lines: "22-28"
    symbols: ["CliLifecycleCommand"]
  - path: "cli/src/main/kotlin/io/github/amichne/kast/cli/command/lifecycle/LifecycleCommands.kt"
    lines: "22-134"
    symbols: ["lifecycleCommands"]
icon: "terminal"
keywords: ["CLI", "commands", "schema", "operations"]
---

{{/* Generated by docs/generate_cli_reference.py. Do not edit. */}}

This page is generated from the typed operation registry and Kotlin command
graph used by `kast --schema`. The documentation check fails when they differ.

Use:

- `kast --schema` for the machine-readable contract.
- `kast <command> --help` for every option and valid combination.

## Installed server projection

**Bottom line:** the installed `serverProjection` is the broker contract. Read
it from the exact configured `kast` executable; never infer tools from a
version string or human-readable command text.

The projection defines:

- Every operation marked `public`, with its canonical ID and evidence document.
- Tool names, descriptions, and closed input and output JSON Schemas.
- Field-to-CLI bindings, deferred loading, and approval policy.
- Read façades such as `workspace_ensure_ready`, `symbol_lookup`,
  `semantic_query`, `impact_analyze`, and `diagnostic_check`.

Approval is closed:

- Read tools use `none`.
- Every `change_*` tool requires `explicit` approval.

Sources: [projection generation](https://github.com/amichne/kast/blob/main/cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt)
and [broker admission](https://github.com/amichne/kast/blob/main/cli/src/main/kotlin/io/github/amichne/kast/cli/broker/provider/KastProvider.kt).

## Sidecar endpoint operations

Run semantic commands from the repository root.

- Success emits one JSON document on standard output.
- Rejection emits one diagnostic document on standard error.
- The private IntelliJ sidecar publishes {public_count} operations marked
  `public` by the generated registry.

| Operation | Command shape | Result role |
| --- | --- | --- |
{hosted_rows}
{deferred_section}

## Continuation argument limits

Pass a relation or traversal continuation intact with `--continuation <token>`
or `--continuation=<token>`. The CLI admits these family-specific envelopes
under the canonical public text bound (1,048,576 characters).

Ordinary arguments remain limited to 4,096 characters and an invocation to 66 arguments. Corrupt,
wrong-family, and over-bound continuations reject before runtime dispatch.

The operating system also limits the combined argument and environment size;
the canonical parser bound does not promise that every host can launch a process
with a one-megabyte argument.

## Runtime lifecycle

Kast owns the isolated sidecar lifecycle.

- `start` and semantic commands may launch a release-line-compatible local IDEA build.
- `status` and `stop` stay passive; they never manufacture an endpoint.
- The release pair defines compatible platform lines, not patch equality.
- Admission retains the exact observed build pair in runtime identity.

Process ownership is explicit:

- Unset or `KAST_ENABLE_LAUNCHD=0`: launch directly.
- `KAST_ENABLE_LAUNCHD=1`: use launchd.
- Any other value: reject the request.

| Command | Effect |
| --- | --- |
{lifecycle_rows}

## Process-local commands

These commands stay inside the installed control product; neither is a
sidecar endpoint operation.

| Command | Result |
| --- | --- |
{local_command_rows}

## Default local traces

Ready endpoints write topology and traversal spans to a private folder derived
from the exact socket namespace.

`kast product inspect` reports:

- Format and enabled state.
- `directoryPath` and `traceFilePath`.

The directory uses mode `0700`; trace files use `0600`. Allowlisted span fields
exclude paths, selectors, source text, exception messages, and stack traces.

## Process-local flags

These flags do not contact or start the IntelliJ sidecar.

| Flag | Result |
| --- | --- |
{local_rows}

Next:

- First run: [Set up and start Kast](/start).
- Outcome semantics: [Trust the evidence](/concepts/evidence-boundaries).
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
