# ADR 0020: Compact public agent result projections

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 only for public agent result projection and
supersedes ADR 0015 and ADR 0016 only for the default mutation and symbol
result shapes. Their backend fidelity, lifecycle, exact-lookup, and
fail-closed rules remain authoritative.

## Decision

Public `kast agent` symbol, impact, diagnostics, mutation, operation, and
verification commands return compact typed results by default. Detailed backend payloads,
ranking evidence, surrounding-member inventories, raw multi-step envelopes,
and next-request explanations require `--verbose` or `--explain`.

The projection boundary is owned by the Rust CLI after backend response
validation and before output rendering. Backend and Kotlin protocol contracts
remain full fidelity. Compactness is not implemented by deleting arbitrary
JSON paths: each result family parses validated evidence into typed projection
models and serializes a closed public result.

Default symbol lookup requests only the evidence required for identity,
location, lookup mode, ambiguity, and explicitly requested relationships.
`--limit` bounds detailed reference evidence work and caller traversal output.
Compact relationship requests cap requested and emitted records at four per
requested kind and report an explicit `EXACT` or `KNOWN_MINIMUM` cardinality, `returnedCount`,
`truncated`, and `nextPageToken` when paging is available. The shared
references RPC carries a positive backend limit and an opaque, one-use token
for bounded server-held continuation state. The state binds the workspace,
resolved query and options, INDEX or IDEA evidence source, and source
generation. Indexed lookup uses ordered `LIMIT + 1` SQL pages and resolves only
the bounded rows. The store returns each page and its generation atomically
under one lock, and every committed index-content transition advances that
generation. IDEA fallback retains a lazy exhaustive traversal of every
in-scope Kotlin source path. It accounts for path, PSI-file, leaf, reference,
and compiler-provider probes; checks elapsed time throughout discovery and
resolution; and resumes across empty or oversized pages. It uses no source-text
or spelling prefilter. A bounded local compiler reference provider supplements
leaf resolution for implicit Kotlin convention sites, and any exhausted or
failed evidence domain is reported as partial rather than exhaustive.
Unknown, replayed, mismatched, evicted, and stale tokens fail with a typed
conflict. Continuations cannot reinterpret an offset if index readiness or PSI
changes, and deterministic accepted pages do not overlap. PSI generation is
captured and validated inside the same IDEA read epoch as target traversal.
Server-held state expires after a bounded lifetime, is disposed exactly once
on consumption, mismatch, expiry, eviction, replacement, failure, or natural
exhaustion, and is closed when the backend runtime or project shuts down.
Caller and type hierarchy resolvers still enumerate their underlying compiler
search before the hierarchy engine applies its typed request cap. Their compact
output and request size are bounded, but pre-materialization resolver budgets
are a known limitation owned by the issue #339 follow-up; callers must not infer
bounded backend enumeration from compact cardinality. Impact
uses the same four-node compact request cap, while its SQLite owner counts
cardinality separately and fetches only `limit + 1` ordered rows.
Documentation, surrounding lines and members, ranking traces, and next-request
explanations are requested only for a detailed view. Default diagnostics request
at most eight records and expose semantic completeness, exact full-set severity
counts, exact cardinality, and actionable diagnostics without workspace refresh
step envelopes. Compiler messages and previews are bounded to 256 and 160
characters respectively, with explicit `messageTruncated` and
`previewTruncated` evidence. The first diagnostics page captures a full exact
server-held snapshot. `--limit` controls detailed diagnostic pages and
`--page-token` consumes an opaque, one-use continuation bound to the ordered
files, limit, and Kotlin PSI generation. Continuations reuse the snapshot
without refresh or recomputation; unknown, replayed, mismatched, evicted, and
stale tokens fail with a typed conflict. Snapshot construction and PSI
generation capture share one IDEA read epoch, so a concurrent write cannot be
misattributed to the snapshot. Diagnostic continuation state follows the same
expiry and backend/project shutdown lifecycle as reference continuation state.
Mutation results expose one terminal result, deduplication evidence, affected
files and edits when available, and a diagnostic summary. Failed and
applied-invalid results retain their typed failure, protocol request identity
and details, and exact already-applied edit evidence including replacement
text. Verification exposes
backend/runtime health and capability evidence without its raw step envelopes.
Impact exposes a bounded source-index node set, query identity, confidence
summary, and total/returned/truncated cardinality.

## Selection Contract

Every affected command family owns its field vocabulary as a Clap value enum.
`--fields` accepts a comma-delimited selection for that family. Unknown fields
fail in argument parsing, and a field from another family is incompatible by
construction. `--count` selects a separate typed aggregate result and conflicts
with `--fields`, `--verbose`, and `--explain`.

Symbol counts report result or candidate cardinality and a relationship
known-minimum aggregate with an explicit exactness flag. Impact fields are `query`, `summary`, `nodes`, and `confidence`; impact
counts retain total, returned, and truncated cardinality. Diagnostics counts retain requested, analyzed, and skipped file counts
plus exact full-set diagnostic severities and diagnostic cardinality. Mutation counts retain terminal result
plus edit, file, and diagnostic counts. Verification counts
report checks, failures, and read and mutation capability counts.

`--verbose` preserves the complete validated command envelope. `--explain`
requests evidence-bearing backend fields and preserves the detailed validated
result, including indexed-exact fallback evidence. Neither option weakens exact identity, semantic completeness,
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
| Impact | 120 | 1,500 |
| Diagnostics | 200 | 2,500 |
| Mutation and operation | 100 | 1,200 |
| Verification | 100 | 1,200 |

Fixtures must contain oversized detail fields so a projection regression fails
the budget rather than passing because the input happened to be small. Required
identity, completeness, state, and error evidence cannot be removed merely to
meet a budget.

Exact totals are never inferred from a bounded page. References use the
discriminated `EXACT` or `KNOWN_MINIMUM` union and emit `KNOWN_MINIMUM` until
their evidence source is exhausted. Diagnostics statically expose only
`EXACT`, because the compiler collection establishes the full snapshot before
paging; generated protocol schemas must not admit `KNOWN_MINIMUM` there.

## Source Of Truth

| Contract | Owner |
| --- | --- |
| Public flags and family field enums | `cli-rs/src/cli/agent.rs` |
| Typed compact, selected, count, and detailed projections | `cli-rs/src/agent/projection.rs` |
| Request-side detail switches and validated command orchestration | `cli-rs/src/agent/` |
| Shared reference limit and paging contract | `analysis-api`, `analysis-server` |
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
./gradlew :analysis-api:test :analysis-server:test
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
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
