# ADR 0007: Typed scope mutation agent commands

Status: Accepted

Date: 2026-07-08

This ADR supersedes ADR 0006 only for the typed agent mutation surface. ADR
0006 remains authoritative for system boundaries, hidden catalog surfaces,
setup, readiness, output, distribution, and audit posture.

## Decision

Kast's public typed agent CLI now includes compiler-backed scope mutation
commands in addition to identity-first rename:

| Command | Public role | Apply gate |
| --- | --- | --- |
| `kast agent add-file` | Create a new Kotlin file from a content file. | `--apply` |
| `kast agent add-declaration` | Insert declaration content into a file or named scope. | `--apply` |
| `kast agent add-implementation` | Insert implementation content into a file or named scope. | `--apply` |
| `kast agent add-statement` | Insert statement content into a named executable scope. | `--apply` |
| `kast agent replace-declaration` | Replace a named declaration using declaration-scope evidence. | `--apply` |

Without `--apply`, each command returns a structured mutation plan containing
the typed JSON-RPC request it would apply. With `--apply`, the CLI sends only
the typed `symbol/*` request for that operation.

## Boundaries

These commands are public because they preserve the ADR 0006 rule that agent
automation uses typed noun/verb commands with shallow flags. They do not make
`kast agent call`, `kast agent workflow`, `kast agent tools`, raw edit RPCs,
generated catalogs, offset-based mutation, or arbitrary request files public.

Mutation content is supplied through `--content-file` so agents can prepare and
review the exact source text before applying it. Placement is typed as either a
file scope or a named declaration scope plus one explicit anchor. Declaration
replacement requires a symbol selector and may be refined with `--kind`,
`--file-hint`, and `--containing-type`.

## Source Of Truth

| Layer | Owner |
| --- | --- |
| Public CLI flags and dry-run plans | `cli-rs/src/cli/agent.rs`, `cli-rs/src/agent/dispatch.rs` |
| Mutation request and response contracts | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt` |
| JSON-RPC dispatch and validation workflow | `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt`, `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt` |
| Public docs and packaged guidance | `docs/commands/agent.md`, `cli-rs/resources/kast-skill/SKILL.md` |

## Validation Commands

Use focused checks for the changed surface, then broaden before release:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workflow_smoke --test agent_output_format_smoke
./gradlew :analysis-api:test :analysis-server:test
.github/scripts/test-docs-content-contract.sh
git diff --check
```

Run `kast --output toon agent diagnostics --workspace-root "$PWD"` for touched
Kotlin files when the backend is available, and treat Gradle as authoritative
if the IDE index is transiently stale.

## Change Rule

Further public mutation expansion must add or supersede this ADR before docs,
catalogs, packaged skills, or generated protocol artifacts are rewritten.
New commands must remain plan-first, typed, structured, and gated by
`--apply`.
