# Compact Agent Result Projections Design

## Goal

Make public agent commands return only the evidence an agent requested while
retaining full backend detail behind explicit verbose and explain views.

## Chosen Architecture

The Rust agent layer owns a typed projection boundary after response validation
and before rendering. Command-specific projection arguments travel with the
typed CLI command, so default symbol requests avoid generating documentation,
member inventories, ranking evidence, and next-request explanations in the
first place. Relationship requests carry the same typed result budget that the
projection enforces: references preserve backend `PageInfo`, and caller
traversal receives explicit total and per-node limits. The projection then
parses the validated command result into a family model and emits one of four
closed views: compact, selected fields, counts, or detailed.

This keeps the backend protocol authoritative and full fidelity. It also avoids
an untyped recursive JSON filter: each family defines its own accepted fields,
input contract, and output structures. Unknown field names fail in Clap, while
missing or malformed backend evidence produces a typed agent error.

## Views

- Compact is the default. There is no redundant `--brief` flag.
- `--fields a,b` is a family-specific selected projection.
- `--count` returns family-specific aggregates and conflicts with field or
  detailed selection.
- `--verbose` preserves the current complete validated envelope.
- `--explain` requests evidence-bearing backend fields and returns the detailed
  validated result.

## Family Contracts

Symbol projections retain lookup mode, confidence mode, outcome, ambiguity,
source, compact identity and location, candidates where ambiguity or discovery
requires them, and only explicitly requested relationships. Each relationship
reports total and returned cardinality, truncation, and the next page token
when one exists. Diagnostics retain
semantic outcome, requested/analyzed/skipped counts, severity counts, and
actionable locations/messages. Mutation and operation projections retain stable
identifiers, kind, lifecycle state, edit application state, cancellation,
affected edits/files when a result supplies them, exact edit replacement text,
lossless protocol-error identity and details, and diagnostic aggregates.
Verification retains health, runtime/backend/workspace identity, and read and
mutation capabilities without raw steps. Impact projections retain the queried
symbol and depth, bounded impact nodes, confidence evidence, and explicit
total/returned/truncated cardinality.

## Alternatives Rejected

Contracting Kotlin/backend responses would eliminate the detailed recovery and
debug path and needlessly widen protocol scope. Applying generic JSON paths at
the renderer would accept incompatible fields too late, silently tolerate
schema drift, and make type-safe evidence impossible to audit.

## Budget Proof

Integration fixtures include intentionally oversized details that compact
projections must omit. Pretty JSON is measured by physical lines and
`cl100k_base` tokens. The exact budgets live in ADR 0020 and the test constants,
so reviews see both policy and executable proof.

## Scope

The change covers `agent symbol`, `impact`, `diagnostics`, `verify`, all public
mutation submission commands, and `agent operation status|cancel`. Public
impact is included because it shares the compact-result promise and its
existing metrics request must enforce, rather than merely declare, its typed
limit.
