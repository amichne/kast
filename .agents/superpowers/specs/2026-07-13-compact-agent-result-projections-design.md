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
projection enforces: compact mode requests at most four records, references
accept an opaque one-use handle for server-held, query/workspace/source/generation-bound
continuation state and preserve backend `PageInfo`, and caller
traversal receives explicit total and per-node limits. Impact compact mode also
requests at most four nodes; the metrics database counts all matching nodes
separately and fetches only `limit + 1` ordered rows. The projection then
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
reports an explicit exact or known-minimum cardinality, returned cardinality,
truncation, and the next page token when one exists. The continuation binds the
workspace, query options, INDEX or IDEA evidence domain, and source generation;
unknown, replayed, mismatched, evicted, or stale tokens conflict. Indexed SQL
returns its ordered page and generation atomically, and every committed
index-content transition advances the generation. Lazy IDEA reference traversal
visits every in-scope Kotlin source path without source-text or spelling
prefilters, accounts for path and PSI work, and resumes across empty and
oversized pages. Leaf resolution plus a bounded local compiler provider covers
explicit and implicit Kotlin convention references; an exhausted or failed
evidence domain is reported as partial. PSI generation capture and validation
share the traversal read epoch. Server-held continuations have bounded lifetime
and capacity, dispose owned state exactly once on every terminal path, and are
closed by backend runtime and project shutdown.
Diagnostics request eight records in compact mode and retain semantic outcome,
requested/analyzed/skipped counts, exact full-set severity/cardinality, and
actionable locations/messages. Messages and previews are character-bounded with
explicit truncation evidence. The first diagnostics page captures an exact
server-held snapshot, and opaque one-use continuations reuse it without refresh
or recomputation while its ordered-file, limit, and PSI-generation binding remains valid.
Snapshot construction and generation capture share one IDEA read epoch, and
diagnostic state follows the same bounded expiry and shutdown lifecycle.
Mutation and operation projections retain stable
identifiers, kind, lifecycle state, edit application state, cancellation,
affected edits/files when a result supplies them, exact edit replacement text,
lossless protocol-error identity and details, and diagnostic aggregates.
Verification retains health, runtime/backend/workspace identity, and read and
mutation capabilities without raw steps. Impact projections retain the queried
symbol and depth, bounded impact nodes, confidence evidence, and explicit
total/returned/truncated cardinality.

## Alternatives Rejected

Removing detailed Kotlin/backend evidence would eliminate the recovery and
debug path. Unbounded backend collection would make compactness cosmetic, so
reference and diagnostic page limits are enforced at their typed work/transport
boundaries. Applying generic JSON paths at
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
limit at the SQL boundary.
