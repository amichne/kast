<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-12 | hash: 1440d58995b7 -->

# app-server

## Purpose

Owns the persistent broker/coordinator, host attachment, existing-IDE invocation routing, and Codex protocol projection.

## Key Files

- [README.md](README.md) - architecture, lifecycle, status, and public integration behavior.
- [docs/compatibility.md](docs/compatibility.md) - tested compatibility evidence and open release gates.
- [docs/public-query-contract.md](docs/public-query-contract.md) - externally visible query semantics.
- [src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt](src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt) - Codex-facing executable entry.
- [src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt) - installed coordinator assembly.
- [src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt) - passive coordinator status and retired-worker rejection.
- [src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt](src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt) - Codex protocol boundary.

- [src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt](src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt) - intent-tool admission and schema identity.

## Subdirectories

- `src/main/kotlin/io/github/amichne/kast/appserver/core` - pure broker/session domain.
- `src/main/kotlin/io/github/amichne/kast/appserver/host` - CLI, desktop, and installed-client host adapters; read its nested `AGENTS.md` first.
- `src/main/kotlin/io/github/amichne/kast/appserver/protocol` - Codex and Copilot projections plus thread catalog state.
- `src/main/kotlin/io/github/amichne/kast/appserver/provider` - process, Gradle, invocation, and observer boundaries.
- `src/main/kotlin/io/github/amichne/kast/appserver/runtime` - workspace registration, broker sessions, and invocation control.
- `src/test` - focused behavior tests; [native acceptance](src/test/kotlin/io/github/amichne/kast/appserver/acceptance) contains integration harnesses.

## Entry Points

- Gradle project: `:app-server`.
- Runtime commands are surfaced through [App Server commands](../cli/src/main/kotlin/io/github/amichne/kast/cli/command/appserver) and [Codex commands](../cli/src/main/kotlin/io/github/amichne/kast/cli/command/codex).

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/runtime-hosts.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For transport/session faults, read `README.md`, then `core`, `host`, and protocol tests.
- For coordinator readiness, start with `CoordinatorControl`, `CoordinatorStatus`, and `InstalledCoordinatorClient`. Status admits exactly zero isolated workers.
- For public tool shape, begin with [public tool contracts](../knowledge/contracts/public-tools.md), then `query/PublicToolContract.kt` and the schema generator. For Codex projection, follow `protocol/codex`.
