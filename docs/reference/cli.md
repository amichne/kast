---
type: Reference
title: CLI Reference
description: The public Kast command surface for coding agents.
tags: [cli, reference, commands, agents]
code_sources:
  - path: cli-rs/src/interface/cli/agent/agent_surface.rs
  - path: cli-rs/src/agent/adapter/mod.rs
  - path: cli-rs/src/main.rs
---

# CLI Reference

`kast` is the only public interface. It discovers the nearest Gradle workspace
from the current directory and emits compact TOON without output-format or
schema controls.

Run `kast` with no command to inspect the selected root, runtime readiness,
reference-index readiness, limitations, and suggested next commands.

## Commands

| Command | Result |
| --- | --- |
| `kast up` | Start or reuse the exact-root semantic runtime and wait for usable compiler evidence. |
| `kast refresh [PATH...]` | Refresh changed or selected Kotlin files, reference evidence, and their persisted graph facts. |
| `kast refresh external <FAILURE_ID>...` | Accept eligible file-local indexing failures as explicit external `UNKNOWN` graph boundaries. |
| `kast files [PATTERN]` | List Kotlin source and script files. |
| `kast symbol find <QUERY>` | Find compiler-backed symbol identities. |
| `kast symbol show <SYMBOL>` | Show one symbol. |
| `kast symbol refs <SYMBOL>` | Find references. |
| `kast symbol callers <SYMBOL>` | Find incoming callers. |
| `kast symbol callees <SYMBOL>` | Find outgoing callees. |
| `kast symbol implementations <SYMBOL>` | Find implementations. |
| `kast symbol supertypes <SYMBOL>` | Traverse supertypes. |
| `kast symbol subtypes <SYMBOL>` | Traverse subtypes. |
| `kast graph [summary]` | Report persisted graph generation and cardinality. |
| `kast graph nodes` | Enumerate generation-pinned graph nodes. |
| `kast graph neighbors <SYMBOL>` | Report adjacent nodes. |
| `kast graph topology` | Report topology statistics. |
| `kast graph communities` | Report deterministic graph communities. |
| `kast graph impact <SYMBOL>` | Report bounded source impact. |
| `kast check [PATH...]` | Refresh and report compiler diagnostics for changed or selected files. |
| `kast change rename <SYMBOL> <NEW_NAME>` | Validate a compiler-resolved rename. |
| `kast change add-file <PATH>` | Validate a Kotlin file whose content comes from standard input. |
| `kast change add-declaration <PATH>` | Validate a declaration appended to one file; content comes from standard input. |
| `kast change add-implementation <SCOPE>` | Validate implementation content appended to one named scope from standard input. |
| `kast change add-statement <SCOPE>` | Validate a statement appended to one named executable scope from standard input. |
| `kast change replace <SYMBOL>` | Validate replacement content from standard input. |
| `kast apply <PLAN_ID> --lease-id <LEASE_ID>` | Apply the validated plan with retry-safe exact-root authority. |

Use `kast <command> --help` for the small operation-specific grammar.

Applied mutations require a lease from
`kastctl agent lease acquire --workspace-root "$PWD"`. Pass its opaque
`leaseId` to `kast apply`, then release it with
`kastctl agent lease release --workspace-root "$PWD" --lease-id <LEASE_ID>`
when the mutation session ends.

When `files`, a symbol relationship, `graph nodes`, or `graph impact` returns
`nextPage`, repeat the same command with `--page <nextPage>`. The continuation
binds its workspace and query without exposing backend paging controls.

## Boundary semantics

Diagnostics do not block reference indexing. An eligible file-local failure is
reported with a content-bound identifier. Externalizing that identifier keeps
the failure visible, removes unsupported outgoing facts, and records the file
as an `UNKNOWN` graph boundary. Cancellation, storage corruption, protocol
failure, and workspace failure remain terminal.

An empty result is not evidence of completeness. Read the returned coverage,
limitations, and next action.

## Internal control plane

The private release-local `libexec/kastctl` multicall entrypoint retains setup,
developer, release, raw RPC, and legacy command families for Kast-owned
automation. It is not placed on `PATH`, is not an agent interface, and is
intentionally omitted from installed skills. Maintainer documentation uses its
release-local path when one of those operations is required.
