<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: c2f0e404ad37 -->

# app-server

## Purpose

Owns the persistent broker/coordinator, host attachment, workspace runtime supervision, and Codex protocol projection.

## Key Files

- [README.md](README.md) - architecture, lifecycle, status, and public integration behavior.
- [docs/compatibility.md](docs/compatibility.md) - tested compatibility evidence and open release gates.
- [docs/public-query-contract.md](docs/public-query-contract.md) - externally visible query semantics.
- [src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt](src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt) - Codex-facing executable entry.
- [src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt) - installed coordinator assembly.
- [src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceRuntimeControl.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceRuntimeControl.kt) - workspace runtime lifecycle and admission control.
- [src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt](src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt) - Codex protocol boundary.

## Subdirectories

- `src/main/kotlin/io/github/amichne/kast/appserver/core` - pure broker/session domain.
- `src/main/kotlin/io/github/amichne/kast/appserver/host` - CLI, desktop, and installed-client host adapters; read its nested `AGENTS.md` first.
- `src/main/kotlin/io/github/amichne/kast/appserver/protocol` - Codex and Copilot projections plus thread catalog state.
- `src/main/kotlin/io/github/amichne/kast/appserver/provider` - process, Gradle, invocation, and observer boundaries.
- `src/main/kotlin/io/github/amichne/kast/appserver/runtime` - workspace registration, worker lifecycle, and execution control.
- `src/test` and `acceptance` - focused behavior and integration evidence.

## Entry Points

- Gradle project: `:app-server`.
- Runtime commands are surfaced through [App Server commands](../cli/src/main/kotlin/io/github/amichne/kast/cli/command/appserver) and [Codex commands](../cli/src/main/kotlin/io/github/amichne/kast/cli/command/codex).

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/runtime-hosts.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For transport/session faults, read `README.md`, then `core`, `host`, and protocol tests.
- For workspace concurrency or readiness, start in `runtime` and follow types into `workspace` and `distribution`.
- For tool shape or qualification, start in `protocol/codex` and then `protocol/registry`.
