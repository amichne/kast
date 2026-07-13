# Compact Agent Result Projections Implementation Plan

**Goal:** Ship compact, stable typed result views for symbol, diagnostics,
mutation, operation, and verification commands with explicit detail and
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

- [ ] Add public tests for compact defaults, explicit verbose/explain, each
  family field enum, unknown and incompatible fields, mutually exclusive count
  mode, and line/token budgets with oversized fixtures.
- [ ] Run the new test and confirm RED before production changes.
- [ ] Add family-specific field enums and flattened view arguments; keep parsing
  and incompatibility in Clap rather than stringly runtime validation.

## Task 2: Typed Symbol Projection

**Files:**

- Create: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent/symbol_lookup.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`

- [ ] Add RED tests proving default output omits request, resolution, member,
  documentation, ranking, and next-request detail while retaining identity,
  location, mode/source, ambiguity, and requested relationships.
- [ ] Implement typed symbol input and compact/selected/count projections.
- [ ] Make request detail conditional on explicit verbose/explain and verify
  detailed output retains the omitted evidence.
- [ ] Run focused symbol and budget tests GREEN.

## Task 3: Diagnostics And Verification Projections

**Files:**

- Modify: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/tests/agent_diagnostics_smoke.rs`
- Modify: `cli-rs/tests/runtime_backend_smoke.rs`

- [ ] Add RED tests for completeness/severity output without step envelopes and
  verification health/capability evidence without raw steps.
- [ ] Parse validated result families into typed compact, selected, and count
  models; preserve incomplete-analysis errors without dumping full results.
- [ ] Run focused diagnostics, verification, and budget tests GREEN.

## Task 4: Mutation And Operation Projections

**Files:**

- Modify: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/tests/agent_operation_surface_smoke.rs`

- [ ] Add RED submission, status, active-state, and terminal-result fixtures
  covering identifiers, state, edit application, affected files/edits, and
  diagnostic aggregates.
- [ ] Implement receipt/snapshot parsing and compact/selected/count projection
  without weakening idempotency or cancellation evidence.
- [ ] Run operation and budget tests GREEN.

## Task 5: Public Guidance, Review, And Full Verification

**Files:**

- Modify: `docs/reference/agent-commands.md`
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/quickstart.md`
- Modify: relevant packaged-content tests

- [ ] Document compact default, fields/count, and verbose/explain without
  exposing internal catalog or encoding vocabulary.
- [ ] Run focused tests, formatting, clippy, full Cargo tests, docs contracts,
  Zensical build, and `git diff --check`.
- [ ] Remove generated `.kotlin`, audit the diff, commit coherent slices, write
  `.agent-turn/issue-337-report.md`, and leave the worktree clean without push.
