# ADR 0016: Fail-closed exact symbol lookup

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 only for the public symbol-lookup contract. ADR
0006 remains authoritative for the rest of the product surface, system
boundaries, setup, runtime, output, distribution, and audit posture.

## Decision

`kast agent symbol --query <name>` defaults to exact lookup. The command has a
typed `--mode exact|discovery` selector; lexical or ranked fuzzy candidates are
available only through `--mode discovery`.

Exact lookup applies the query, optional kind, file hint, and containing type
as hard constraints. Backticks are normalized only while comparing Kotlin
identity. Successful responses retain the canonical identity returned by the
compiler or source index.

The exact result is a closed outcome:

| Outcome | Meaning |
| --- | --- |
| `RESOLVED` | Exactly one declaration satisfies every exact constraint. |
| `NOT_FOUND` | No declaration satisfies every exact constraint. |
| `AMBIGUOUS` | Multiple declarations satisfy every exact constraint. |

Every result reports one evidence source: compiler resolution, indexed exact
identity, or fuzzy discovery. Exact `NOT_FOUND` and `AMBIGUOUS` outcomes never
trigger lexical discovery. The CLI may use indexed exact identity only when a
typed compiler or backend availability failure prevents compiler lookup and
the source index can enforce every requested constraint. It must not reinterpret
an operational compiler failure or an unsupported constraint as a match.

Reference and caller requests run only after exact lookup has produced a
resolved compiler identity. They use the canonical fully qualified name from
that response rather than the original query text.

## Rationale

The previous public command ran indexed exact and lexical discovery before
compiler resolution, while the name-based resolver ranked workspace candidates
and chose the first one. An absent or newly unindexed name could therefore put
an unrelated fuzzy declaration first. That behavior looked like identity
resolution even though the request remained unresolved.

Making mode and outcome explicit removes the unsafe implicit transition from
lookup to discovery. Hard constraints and a closed outcome preserve what the
system proved, while the source field lets agents distinguish compiler evidence
from a source-index fallback or intentional fuzzy exploration.

## Compatibility And Migration

The internal `symbol/resolve` method keeps its existing `RESOLVE_SUCCESS` and
`RESOLVE_FAILURE` variants. It adds expected `RESOLVE_NOT_FOUND` and
`RESOLVE_AMBIGUOUS` variants. Internal consumers must exhaustively handle the
two new variants; `RESOLVE_FAILURE` remains reserved for operational failure.

The internal `symbol/query` method keeps its request and response envelope.
Callers that want its historical lexical behavior must request lexical mode
explicitly. The typed CLI supplies that request for `--mode discovery`.

## Source Of Truth

| Contract | Owner |
| --- | --- |
| Public mode and flags | `cli-rs/src/cli/agent.rs` |
| Typed public outcome and orchestration | `cli-rs/src/agent/` |
| Indexed exact and fuzzy query behavior | `cli-rs/src/symbol_query/` |
| Compiler exact result contract | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastResolveResponse.kt` |
| Compiler exact selection | `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt` |
| Internal command catalog | `cli-rs/protocol/source/commands.json` |
| Public guidance | `cli-rs/resources/kast-skill/`, `docs/reference/agent-commands.md`, `docs/use/inspect-kotlin.md` |

Generated protocol artifacts remain outputs of the contract generator. Edit
the catalog and Kotlin contract owners, then regenerate them.

## Validation

Use focused exact-lookup tests before the broad contract gates:

```console
./gradlew :analysis-api:test :analysis-server:test
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke --test symbol_query_smoke
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/test-docs-content-contract.sh
zensical build --clean
git diff --check
```

When the IntelliJ plugin has prepared the current workspace, also run Kast
diagnostics for the changed Kotlin files. A temporary worktree without plugin
metadata must report that limitation instead of running `kast setup` on macOS.

## Change Rule

Any future mode, evidence source, fallback rule, or exact outcome must
supersede this ADR before public docs, packaged guidance, catalogs, or generated
protocol assets change. Exact mode must continue to fail closed; discovery can
never become an implicit fallback.
