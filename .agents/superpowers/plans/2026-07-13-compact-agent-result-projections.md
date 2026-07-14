# Compact Agent Result Projections Implementation Plan

**Goal:** Ship compact, stable typed result views for symbol, impact,
diagnostics, mutation, operation, and verification commands with explicit detail and
machine-selection escape hatches.

**Architecture:** Parse validated backend results into Rust family projection
types before rendering. Command-specific Clap enums make accepted fields closed;
compact, selected, count, verbose, and explain are distinct typed view modes.

**Tech Stack:** Rust 2024, Clap, serde, serde_json, `tiktoken-rs` (dev only),
Cargo integration tests, Markdown docs contracts.

## Task 1: Projection CLI Contract And Budget RED Tests

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Create: `cli-rs/tests/agent_result_projection_smoke.rs`
- Modify: `cli-rs/Cargo.toml`

- [x] Add public tests for compact defaults, explicit verbose/explain, each
  family field enum, unknown and incompatible fields, mutually exclusive count
  mode, and line/token budgets with oversized fixtures.
- [x] Run the new test and confirm RED before production changes.
- [x] Add family-specific field enums and flattened view arguments; keep parsing
  and incompatibility in Clap rather than stringly runtime validation.

## Task 2: Typed Symbol Projection

**Files:**

- Create: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent/symbol_lookup.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`

- [x] Add RED tests proving default output omits request, resolution, member,
  documentation, ranking, and next-request detail while retaining identity,
  location, mode/source, ambiguity, and requested relationships.
- [x] Implement typed symbol input and compact/selected/count projections.
- [x] Make request detail conditional on explicit verbose/explain and verify
  detailed output retains the omitted evidence.
- [x] Run focused symbol and budget tests GREEN.

## Task 3: Diagnostics And Verification Projections

**Files:**

- Modify: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/tests/agent_diagnostics_smoke.rs`
- Modify: `cli-rs/tests/runtime_backend_smoke.rs`

- [x] Add RED tests for completeness/severity output without step envelopes and
  verification health/capability evidence without raw steps.
- [x] Parse validated result families into typed compact, selected, and count
  models; preserve incomplete-analysis errors without dumping full results.
- [x] Run focused diagnostics, verification, and budget tests GREEN.

## Task 4: Mutation And Operation Projections

**Files:**

- Modify: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/tests/agent_operation_surface_smoke.rs`

- [x] Add RED submission, status, active-state, and terminal-result fixtures
  covering identifiers, state, edit application, affected files/edits, and
  diagnostic aggregates.
- [x] Implement receipt/snapshot parsing and compact/selected/count projection
  without weakening idempotency or cancellation evidence.
- [x] Run operation and budget tests GREEN.

## Task 5: Public Guidance, Review, And Full Verification

**Files:**

- Modify: `docs/reference/agent-commands.md`
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/quickstart.md`
- Modify: relevant packaged-content tests

- [x] Document compact default, fields/count, and verbose/explain without
  exposing internal catalog or encoding vocabulary.
- [x] Run focused tests, formatting, clippy, full Cargo tests, docs contracts,
  Zensical build, and `git diff --check`.
- [x] Remove generated `.kotlin`, audit the diff, and freeze the complete
  uncommitted worktree for independent review without commit, push, or rebase.

## Task 6: Review Repair — Bounded Relationships And Impact

- [x] Bound references at the typed backend query boundary; bound caller public
  output and request size while documenting pre-materialization resolver work as
  the explicit #339 deferral; preserve deterministic reference page metadata.
- [x] Add the compact impact family, enforce its limit in SQLite with a
  separate count and `limit + 1` fetch, and budget a high-cardinality database.
- [x] Preserve error details, mutation protocol identity/details, exact edit
  replacement text, final verification step errors, and indexed fallback
  explain evidence.
- [x] Regenerate catalogs and protocol docs, then run Rust, Kotlin, docs, and
  budget gates.
- [x] Extract materially edited public reference request/query models into
  matching files and keep sealed response variants with their response root.

## Task 7: Review Repair — Honest Reference Work And Bounded Diagnostics

- [x] Replace unconditional reference totals with required `EXACT` or
  `KNOWN_MINIMUM` cardinality and preserve the discriminated union in generated
  wire schemas.
- [x] Page indexed references with deterministic `LIMIT + 1` SQL, stop IDEA
  reference streaming at page evidence, and prove real high-cardinality work.
- [x] Replace reference cursors with opaque one-use handles for bounded
  server-held INDEX or lazy IDEA state, bind query/workspace/source/generation,
  and test readiness transitions without offset reinterpretation.
- [x] Add typed diagnostics limits and continuation, exact full-set severity
  counts/cardinality, an eight-record compact cap, and explicit message/preview
  truncation evidence.
- [x] Add 500-record diagnostics page/non-overlap and oversized real-field budget
  tests, plus generated catalog and protocol coverage.
- [x] Run focused and full Kotlin, Rust, docs, generated-contract, formatting,
  lint, and diff gates; freeze the uncommitted tree for fresh review.

## Task 8: Frozen-Review Findings

- [x] Make diagnostics continuation opaque and server-held, reuse one exact
  snapshot without refresh or recomputation, and reject stale, mismatched,
  replayed, unknown, or evicted tokens with a typed conflict.
- [x] Preserve full typed mutation placement identity for file/named scopes and
  at/after/before/statement anchors in compact plans.
- [x] Preserve final refresh/admission failures, canonical diagnostic file paths,
  verification semantic-workspace evidence, and impact workspace identity in
  every compact projection.
- [x] Make diagnostics cardinality statically exact in Kotlin, Rust, OpenAPI,
  examples, and generated catalogs rather than accepting `KNOWN_MINIMUM` and
  rejecting it at runtime.
- [x] Reconcile ADR, design, public guidance, generated contracts, and this plan
  with the implemented boundaries and the explicit #339 deferral.

## Task 9: Final-Review Repair And Main Integration

- [x] Make IDEA reference discovery no-false-negative and genuinely bounded by
  accounting for every visited path and PSI read, checking elapsed time during
  discovery, and preserving lazy resume across nonmatching and oversized files.
- [x] Prove Kotlin convention references for equality, containment, indexed get
  and set, delegation, destructuring/component functions, invocation, aliases,
  and operator sites without spelling heuristics.
- [x] Return indexed pages and their generation atomically under one store lock,
  increment generation for every committed index-content transition, and prove
  production-store mutation rejection between pages.
- [x] Capture and validate PSI generations inside the same read epochs as
  reference traversal and diagnostics snapshot construction, with
  barrier-controlled concurrent-write regressions.
- [x] Add continuation expiry, exact-once disposal, `closeAll`, and runtime and
  project shutdown wiring; prove expiry, eviction, replacement collision,
  mismatch, exception, exhaustion, and shutdown behavior.
- [x] Preserve cumulative indexed search-scope evidence across pages.
- [x] Run focused pre-rebase gates, commit, create a recovery ref, fetch, and
  structurally rebase onto `origin/main` at `6ba55393` or newer while preserving
  #335, #336, #341, and #352 contracts.
- [x] Prove compact/count/fields behavior for relative diagnostics, linked
  worktree admission, mutation rejection, and verify evidence, then run the full
  Kotlin, Rust, clippy, fmt, generated-contract, sample, docs, Zensical, diff,
  and Kast verification matrix and freeze a clean worktree.

The post-rebase exact-root `kast agent verify` gate passed with linked-worktree,
READY IDEA, and compiler-backed evidence. The companion diagnostics gate
analyzed 31 of 32 changed production Kotlin files, then failed on
`KastPluginBackend.kt` because IDEA reported a stale PSI/index stamp mismatch.
The full Gradle suite compiled and tested the same disk state successfully; no
unsafe global IDE save, reload, or close action was taken to clear user state.
