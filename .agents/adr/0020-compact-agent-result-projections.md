# ADR 0020: Compact public agent result projections

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 only for public agent result projection and
supersedes ADR 0015 and ADR 0016 only for the default mutation and symbol
result shapes. Their backend fidelity, lifecycle, exact-lookup, and
fail-closed rules remain authoritative.

## Decision

Public `kast agent` symbol, diagnostics, mutation, operation, and verification
commands return compact typed results by default. Detailed backend payloads,
ranking evidence, surrounding-member inventories, raw multi-step envelopes,
and next-request explanations require `--verbose` or `--explain`.

The projection boundary is owned by the Rust CLI after backend response
validation and before output rendering. Backend and Kotlin protocol contracts
remain full fidelity. Compactness is not implemented by deleting arbitrary
JSON paths: each result family parses validated evidence into typed projection
models and serializes a closed public result.

Default symbol lookup requests only the evidence required for identity,
location, lookup mode, ambiguity, and explicitly requested relationships.
Documentation, surrounding lines and members, ranking traces, and next-request
explanations are requested only for a detailed view. Default diagnostics expose
semantic completeness counts and actionable diagnostics without workspace
refresh step envelopes. Mutation results expose operation state, edit
application state, affected files and edits when available, and a diagnostic
summary. Verification exposes backend/runtime health and capability evidence
without its raw step envelopes.

## Selection Contract

Every affected command family owns its field vocabulary as a Clap value enum.
`--fields` accepts a comma-delimited selection for that family. Unknown fields
fail in argument parsing, and a field from another family is incompatible by
construction. `--count` selects a separate typed aggregate result and conflicts
with `--fields`, `--verbose`, and `--explain`.

Symbol counts report result or candidate cardinality and requested relationship
counts. Diagnostics counts retain requested, analyzed, and skipped file counts
plus diagnostic severities. Mutation counts retain operation and edit
application state plus edit, file, and diagnostic counts. Verification counts
report checks, failures, and read and mutation capability counts.

`--verbose` preserves the complete validated command envelope. `--explain`
requests evidence-bearing backend fields and preserves the detailed validated
result. Neither option weakens exact identity, semantic completeness,
idempotency, or mutation-state validation.

## Output And Budget Contract

The projection is independent of rendering. Readable output and structured
JSON keep the same public field names and states. Internal encoding choices do
not define a second projection vocabulary.

Representative JSON audit fixtures are gated with both line limits and the
stable `cl100k_base` tokenizer. Default budgets are:

| Result family | Maximum lines | Maximum tokens |
| --- | ---: | ---: |
| Symbol | 120 | 1,500 |
| Diagnostics | 200 | 2,500 |
| Mutation and operation | 100 | 1,200 |
| Verification | 100 | 1,200 |

Fixtures must contain oversized detail fields so a projection regression fails
the budget rather than passing because the input happened to be small. Required
identity, completeness, state, and error evidence cannot be removed merely to
meet a budget.

## Source Of Truth

| Contract | Owner |
| --- | --- |
| Public flags and family field enums | `cli-rs/src/cli/agent.rs` |
| Typed compact, selected, count, and detailed projections | `cli-rs/src/agent/projection.rs` |
| Request-side detail switches and validated command orchestration | `cli-rs/src/agent/` |
| Readable and structured rendering | `cli-rs/src/output/` |
| Public usage guidance | `docs/reference/agent-commands.md`, `cli-rs/resources/kast-skill/` |
| Line and token budget gates | `cli-rs/tests/agent_result_projection_smoke.rs` |

Generated catalogs and protocol artifacts remain internal full-fidelity
contracts. They are not public compact result definitions.

## Validation

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke --test agent_diagnostics_smoke --test agent_operation_surface_smoke --test runtime_backend_smoke
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

## Change Rule

New result families, fields, count semantics, detailed views, or budget changes
must preserve typed selection, fail-closed validation, backend fidelity, and the
evidence required by ADRs 0015 and 0016. A generic JSON path or arbitrary field
name is not an acceptable public extension point.
