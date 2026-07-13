# Fail-Closed Semantic Analysis Design

Date: 2026-07-13

Issue: [#332](https://github.com/amichne/kast/issues/332)

Status: Approved for autonomous implementation

## Objective

Make a diagnostics response prove whether every requested file was analyzed.
Backend reachability, workspace refresh acknowledgement, and JSON-RPC transport
success must remain distinct from semantic completeness.

The defining regression is a two-step `kast agent diagnostics` invocation in
which workspace refresh succeeds, the backend returns an
`ANALYSIS_FAILURE: File not found` diagnostic, and the command nevertheless
reports `ok: true` with exit status zero.

## Requirements

1. Every requested file has one typed response state: `ANALYZED`,
   `PENDING_INDEX`, `OUTSIDE_SOURCE_MODULES`, `MISSING_ON_DISK`, or
   `BACKEND_FAILURE`.
2. A diagnostics result is complete only when every requested file is
   `ANALYZED` and no diagnostic has code `ANALYSIS_FAILURE`.
3. An incomplete result produces a failed typed agent command and process exit
   status one even when refresh and JSON-RPC transport succeeded.
4. Ordinary Kotlin compiler errors remain successful semantic analysis. They
   may make a mutation validation dirty, but they do not mean the file was
   skipped.
5. Diagnostics responses expose requested, analyzed, and skipped file counts
   plus a typed semantic outcome in human, JSON, and TOON output.
6. Mutation validation summaries preserve the same completeness evidence and
   cannot report clean when semantic analysis was incomplete.
7. Existing raw diagnostics, typed agent diagnostics, and mutation command
   names remain unchanged.

## Considered Approaches

### Parse diagnostics in the CLI

The CLI could scan diagnostic codes and fail if it sees `ANALYSIS_FAILURE`.
This is a useful defensive check but is insufficient as the primary contract:
it cannot distinguish missing, pending, outside-module, and backend-failure
states, and it cannot prove a file was omitted without any diagnostic.

### Fail the whole RPC request

The backend could throw as soon as one file cannot be analyzed. This fails
closed but discards useful evidence for the other files in a batch and cannot
report per-file terminal states or counts.

### Per-file completeness ledger

The selected approach adds a typed per-file ledger to `DiagnosticsResult` and
derives a summary from it. The backend returns evidence for every requested
file, the server preserves it, and the CLI converts an incomplete semantic
outcome into a failed agent step. A defensive CLI check also rejects any
`ANALYSIS_FAILURE` diagnostic even if a malformed backend claims completeness.

## Contract Model

`analysis-api` will own three host-agnostic types:

- `FileAnalysisState`, the closed state enumeration;
- `FileAnalysisStatus`, the normalized absolute file path, state, and optional
  explanation for one request entry;
- `SemanticAnalysisOutcome`, with `COMPLETE` and `INCOMPLETE` variants.

`DiagnosticsResult` retains its existing `diagnostics` and pagination fields
and adds:

- ordered `fileStatuses`;
- `semanticOutcome`;
- `requestedFileCount`;
- `analyzedFileCount`;
- `skippedFileCount`.

Construction validates that counts match the ledger, analyzed plus skipped
equals requested, the outcome matches skipped-file evidence, and an
`ANALYSIS_FAILURE` diagnostic can never coexist with `COMPLETE`. Pagination may
truncate diagnostic items but never changes completeness evidence.

The public wire-model change is additive and stays on the current protocol
schema version. Generated OpenAPI, protocol reference pages, and examples are
regenerated from the Kotlin model owners. This avoids redefining unrelated
schema-versioned surfaces while making new backends and the version-coupled CLI
emit the stronger evidence.

## IDEA Backend Classification

The IDEA backend evaluates requested paths independently and preserves request
order. Classification precedence is:

1. absent on the filesystem: `MISSING_ON_DISK`;
2. outside the active workspace or an IDEA source module:
   `OUTSIDE_SOURCE_MODULES`;
3. present but not admitted to VFS/PSI, or project indexing still in progress:
   `PENDING_INDEX`;
4. admitted Kotlin source successfully analyzed, with or without ordinary
   compiler diagnostics: `ANALYZED`;
5. unexpected analysis exception: `BACKEND_FAILURE`.

Every non-analyzed state also emits an error diagnostic with code
`ANALYSIS_FAILURE` for compatibility with existing consumers. Cancellation and
IDE process-cancellation exceptions remain operation cancellation; they are not
misreported as per-file backend failures.

Issue #335 will later add bounded waiting for VFS/PSI admission. This issue
only makes the current lack of admission explicit and fail-closed.

## Server and Mutation Flow

`raw/diagnostics` continues to serialize `DiagnosticsResult`. Pagination is
applied only to diagnostic items, leaving the file ledger and counts intact.

The internal mutation validation summary gains the semantic outcome and file
counts. Its `clean` flag is true only when semantic analysis is complete and no
ordinary compiler error is present. Zero affected files produce a complete
zero-count summary.

## CLI Flow

The Rust response boundary recognizes two semantic failure signals:

- `semanticOutcome == INCOMPLETE`;
- any diagnostic with `code == ANALYSIS_FAILURE`.

Either signal makes the diagnostics step fail with a stable semantic-analysis
error. `execute_agent_steps` then sets command `ok` to false and `run` returns
exit status one. Refresh success remains visible as its own successful step.

For diagnostics, the agent command promotes the typed outcome and requested,
analyzed, and skipped counts into a command-level `semanticAnalysis` summary.
The shared structured renderer makes the same fields available in human, JSON,
and TOON modes.

## Testing

Tests will prove:

- contract construction and serialization enforce count/outcome invariants;
- the fake backend returns complete ledgers for clean and broken Kotlin files;
- IDEA returns `ANALYZED` for ordinary compiler diagnostics and a typed
  non-analyzed state for missing or unavailable files;
- server dispatch preserves completeness evidence through pagination;
- mutation summaries are not clean when semantic evidence is incomplete;
- a fake daemon can acknowledge refresh, return a missing-file analysis
  result, and cause `kast agent diagnostics` to emit `ok: false` with exit one;
- command-level counts appear in JSON, TOON, and human output;
- existing clean and ordinary compiler-diagnostic paths retain their behavior.

Focused validation covers `analysis-api`, `analysis-server`, `backend-idea`,
and Rust agent tests. Final validation runs the full Gradle and locked Rust
suites, formatting, Clippy with warnings denied, generated-contract checks, and
diff hygiene.

## Non-Goals

- waiting for newly created files to enter IDEA semantic scope (#335);
- persistent observable mutation operations (#333);
- changing runtime readiness or daemon lifecycle models;
- adding or restoring arbitrary public JSON-RPC command surfaces;
- treating ordinary Kotlin compiler errors as transport or completeness
  failures.
