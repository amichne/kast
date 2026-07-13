# Identity-first Relationship Navigation Design

## Goal

Turn a compiler-resolved Kotlin symbol into a reusable input for bounded
references, callers, callees, implementations, hierarchy, and source-impact
queries without rerunning discovery or emitting lookup explanations on every
step.

## Current failure

On current main, `kast agent symbol` is the only typed path to references and
call hierarchy. It combines lookup with `--references` or `--callers`, so an
agent cannot retain an identity and navigate several relationships as separate
operations. Implementations and type hierarchy exist only as hidden raw
position methods. Impact accepts a fully-qualified symbol but has no public
continuation input.

Issue #337 makes the combined symbol result compact, adds real reference
paging to the backend, bounds call hierarchy work, and gives source impact a
separate count plus bounded SQL fetch. Its current branch also establishes
`ResultCardinality.EXACT|KNOWN_MINIMUM` and a source-bound
`ReferencePageCursor(source, evidenceOffset, returnedBefore)`. Those names are
provisional until #337 lands, but their information is not: #339 must rebase
and wrap the landed types without collapsing either cursor dimension. #337
intentionally does not create standalone relationship commands,
containing-symbol identity, deterministic continuation for every family, or
typed degraded outcomes.

## Considered approaches

### Keep extending `kast agent symbol`

Adding `--implementations`, `--hierarchy`, and impact flags would reuse the
current orchestrator. It would keep identity resolution and navigation
coupled, overload one `--limit` across unrelated work, and repeat lookup
evidence whenever an agent asks a new question. It also makes invalid flag
combinations a growing runtime problem.

### Add one generic `kast agent relation` command

A relation enum would make the command list smaller, but references, call
trees, type trees, implementations, and impact have different directions,
depth rules, records, capabilities, and degraded states. A generic argument
bag would either accept nonsensical combinations or recreate nested
subcommands under an extra routing word.

### Add standalone typed relationship commands

This is the chosen approach. `references`, `callers`, `callees`,
`implementations`, and `hierarchy` each own valid arguments and a closed result
record. Existing `impact` keeps its established name. The command names map
directly to backend or index capabilities and will compose cleanly with the
capability registry that issue #342 completes.

### Pass FQ name plus optional hints

This matches the existing exact-lookup request, but overloads can share FQ
name, kind, declaration file, and containing type. Re-resolving that selector
can become ambiguous after an agent already selected one declaration, so it
does not satisfy direct identity reuse.

### Pass an anchored compiler identity

This is the chosen identity model. Exact lookup returns canonical FQ name,
kind, normalized declaration file, declaration start offset, and optional
containing type. Relationship commands require the file/offset anchor and
verify the other fields against the declaration at that position. The anchor
may become stale after edits, but staleness is detectable as
`SUBJECT_IDENTITY_MISMATCH`; it never silently selects another overload and
requires no server-side session storage.

## Public workflow

The normal sequence is:

```console
kast agent symbol \
  --query OrderService \
  --fields identity,location \
  --workspace-root "$PWD"

kast agent references \
  --symbol com.example.OrderService \
  --declaration-file src/main/kotlin/OrderService.kt \
  --declaration-start-offset 128 \
  --workspace-root "$PWD"

kast agent callers \
  --symbol com.example.OrderService.submit \
  --declaration-file src/main/kotlin/OrderService.kt \
  --declaration-start-offset 244 \
  --depth 2 \
  --workspace-root "$PWD"

kast agent impact \
  --symbol com.example.OrderService \
  --declaration-file src/main/kotlin/OrderService.kt \
  --declaration-start-offset 128 \
  --depth 2 \
  --workspace-root "$PWD"
```

The agent copies `identity.fqName`, `identity.declarationFile`, and
`identity.declarationStartOffset`; kind and containing type are optional hard
assertions. Under explicit `--workspace-root`, `--declaration-file` accepts the
#341 workspace-relative spelling shown above and normalizes it into the same
canonical selector path that results report. A relation command resolves the
anchor directly, verifies the identity, and never falls back to discovery. Its
compact result begins at the relationship, not at the lookup request.

Impact is honest about the current source-index key. When more than one
declaration shares the selected FQ name, the index cannot isolate the anchored
overload. The command returns `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` instead
of labeling FQ-wide aggregate edges as overload-specific impact.

The one-shot `symbol --references`, `--callers`, and `--caller-depth` flags are
removed. This is a deliberate public-surface replacement, not a hidden alias:
the new command names appear in help, packaged guidance, docs, command
contracts, and replacement-focused smoke tests together.

## Command and type model

The Rust CLI adds one selector and bounded argument types shared by concrete
commands:

```rust
struct AgentExactSymbolSelectorArgs {
    symbol: CanonicalSymbolName,
    declaration_file: WorkspaceDeclarationFile,
    declaration_start_offset: DeclarationStartOffset,
    kind: Option<AgentSymbolKind>,
    containing_type: Option<CanonicalSymbolName>,
}

struct AgentRelationLimit(NonZeroU8);       // 1..=200, default 4
struct AgentRelationDepth(NonZeroU8);       // 1..=8, default 1
struct AgentImpactPageOffset(u16);          // 0..=10_000, SQLite only
enum AgentRelationKind {
    References,
    Callers,
    Callees,
    Implementations,
    Hierarchy,
    Impact,
}
```

`AgentRelationPageToken` is an opaque serialized newtype, not a free string.
Its `krp1` payload contains the relation kind, a SHA-256 fingerprint of the
canonical root plus complete anchored selector, declaration-inclusion choice,
direction, depth, and page limit, followed by a tagged, base64url cursor.
Reference cursors losslessly preserve source, evidence offset, and returned
before from #337. Compiler traversal cursors preserve consumed evidence,
returned before, and traversal frontier proof. Impact alone stores a validated
SQLite offset. Decoding checks version, relation, fingerprint, cursor tag, and
the 10,000 evidence-offset ceiling before a runtime session or SQLite
connection is opened.

The concrete Clap commands are:

```text
references       selector + include-declaration + limit + page-token + view
callers          selector + depth + limit + page-token + view
callees          selector + depth + limit + page-token + view
implementations  selector + limit + page-token + view
hierarchy        selector + direction + depth + limit + page-token + view
impact           selector + depth + limit + page-token + view
```

All relationship view families support ADR 0020 compact, fields, count,
verbose, and explain modes. Detailed modes expose full validated backend
evidence for the requested page only; they do not expand the result budget.

## Kotlin contract and backend flow

The internal typed symbol methods remain the bridge from canonical identity to
compiler position:

1. Validate the skill request, anchored selector, positive limit, and typed
   internal cursor.
2. Resolve the declaration file/start offset directly and compare canonical FQ
   name, kind, and containing type with the selector.
3. Return typed subject-not-found or identity-mismatch outcomes without
   searching for or selecting another candidate.
4. Check the required backend capability.
5. Convert the compiler-owned declaration location to the raw backend query.
6. Pass the cursor and candidate-visit budget into the backend provider before
   it materializes references, edges, or inheritors.
7. Drop the extra proof record and return typed page, visited-candidate, and
   #337 cardinality evidence.

`symbol/references` and `symbol/callers` are extended to this contract.
`symbol/implementations` and `symbol/hierarchy` are added as internal typed
methods so the Rust public surface never constructs a raw `FilePosition`.
`callers` and `callees` use `symbol/callers` with fixed incoming and outgoing
directions respectively.

The public reference requirement for containing symbols changes the shared
reference result from bare locations to occurrences:

```kotlin
data class ReferenceOccurrence(
    val location: Location,
    val containingSymbol: ContainingSymbolEvidence,
)

sealed interface ContainingSymbolEvidence {
    data class Known(val symbol: SymbolIdentity) : ContainingSymbolEvidence
    data object TopLevel : ContainingSymbolEvidence
    data class Unavailable(
        val reason: ContainingSymbolUnavailableReason,
    ) : ContainingSymbolEvidence
}
```

Direct sealed variants stay with the root as allowed by the one-type-per-file
rule. `SymbolIdentity` is a lightweight compiler identity containing FQ name,
kind, and declaration location; it does not include documentation, preview,
or declaration source. `ReferenceOccurrence.containingSymbol` is the semantic
identity proof. The existing #337 `Location.usageSiteScope` remains an
optional structural range/source-text field controlled by
`includeUsageSiteScope`; neither field substitutes for the other. The IDEA
references implementation obtains both requested forms while it owns PSI for
the bounded occurrence page. It does not create an N+1 Rust lookup loop or
scan every relation item merely to populate compact output.

Call hierarchy keeps its recursive full-fidelity result internally. The skill
orchestrator flattens edges breadth-first into caller or callee records after
the backend engine has sorted each child set by canonical identity and
location. The emitted window is at most `limit + 1`, but backend work is
bounded separately before materialization. `IdeaCallEdgeResolver` accepts a
typed evidence cursor and visit budget: incoming calls consume deterministic
file/offset candidates, and outgoing calls consume lexical PSI offsets without
first walking the whole declaration. `IdeaTypeEdgeResolver` pages canonical
class-index candidate keys and must not call `findAll()`. Both return
`visitedCandidateCount`, consumed evidence, a next cursor, and exhaustiveness.
Type hierarchy and implementation searches use those adapters. Cycle, timeout,
max-depth, candidate-budget, and backend-limit evidence survives as typed
limitations, and tests instrument provider visits rather than observing only
the final list size.

## Rust result model

Every compact success returns the subject once and a closed item family. The
shared page model is:

```rust
struct AgentRelationPage {
    cardinality: AgentResultCardinality, // Exact | KnownMinimum from #337
    returned_count: usize,
    truncated: bool,
    next_page_token: Option<AgentRelationPageToken>,
    visited_candidate_count: usize,
}
```

Reference records contain `REFERENCE`, a location, and `Known`, `TopLevel`, or
`Unavailable` containing-symbol evidence. Call records contain `CALLER` or
`CALLEE`, the related identity, call-site location, traversal depth, and the
containing identity. Implementation and hierarchy records contain their
specific relation kind, identity, declaration location, and hierarchy depth
where applicable. These are Rust enum variants, not one struct full of
optional fields.

Impact reports an exact total only when the anchored subject is unique at the
FQ-name granularity used by the index. References report exact cardinality only
after exhaustive traversal or from an authoritative exact count covering the
same source/query; bounded or partial IDEA work remains `KNOWN_MINIMUM`.
Calls, implementations, and hierarchy likewise report `EXACT` only after
exhaustion and otherwise report a known minimum at least as large as
`returnedBefore + returnedCount + 1` when another record is proved. The
projection rejects inconsistent returned counts, false exact totals,
truncation without a token, a token without truncation, visited-candidate
claims above the request budget, and relation items of the wrong family.

## Paging and impact

The first request uses the first typed cursor for its family. A successful
non-final page emits the next query-bound token. Reusing the token reconstructs
the lossless internal cursor; all other query values still come from the
current typed command and must match the fingerprint. A reference cursor also
pins `SOURCE_INDEX` or `IDEA`; if that source disappears, the command returns
`REFERENCE_CURSOR_SOURCE_UNAVAILABLE` rather than restarting from another
source.

Issue #337 already gives references the provisional
`ReferencePageCursor(source, evidenceOffset, returnedBefore)` and makes the
SQLite impact query count separately and fetch only `limit + 1` rows. This
issue wraps the landed reference cursor rather than replacing it. It extends
the Rust metrics request with a validated impact offset and changes the impact
row query to `LIMIT limit + 1 OFFSET offset`. The exact count query stays
independent. Ordered rows use depth, source path, target FQ name, and edge kind,
so two pages in an unchanged index have no overlap.

## Error and degradation model

Expected identity outcomes are `AVAILABLE`, `SUBJECT_NOT_FOUND`,
`SUBJECT_IDENTITY_MISMATCH`, and the compatibility-only
`SUBJECT_AMBIGUOUS`. Capability or index absence is `DEGRADED`, with a closed
typed limitation-code enum and the requested capability. A degraded result
never returns an empty page that looks exhaustive.

Only known availability conditions degrade:

- missing `FIND_REFERENCES` becomes `REFERENCES_UNAVAILABLE`;
- missing `CALL_HIERARCHY` becomes `CALL_HIERARCHY_UNAVAILABLE`;
- missing `IMPLEMENTATIONS` becomes `IMPLEMENTATIONS_UNAVAILABLE`;
- missing `TYPE_HIERARCHY` becomes `TYPE_HIERARCHY_UNAVAILABLE`; and
- an unavailable or incompatible source-index database becomes
  `SOURCE_INDEX_UNAVAILABLE`.

An unavailable SQLite reference index is not degraded when #337 can continue
with IDEA reference search. A continuation cannot change its bound reference
source. An anchored overload passed to FQ-keyed impact becomes
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`; no aggregate records are returned as
if they belonged only to that overload.

Malformed backend results, transport failures after capability admission,
query-token mismatches, and exact-root admission failures remain errors. This
prevents degradation from hiding a broken backend.

## Issue boundaries

Issue #338's workspace inventory is not a relation data source. Its public
`filePath` may be copied into `--declaration-file`, but #339 neither edits the
inventory implementation nor infers compiler relationships from index or
filesystem membership. This allows the docs-only and production lanes to
remain independent.

Issue #340 owns Gradle Kotlin DSL task, plugin, dependency, and build-logic
relationships. #339's commands are Kotlin compiler relationships only. A
Gradle script named function must not make `kast agent callers` claim a Gradle
task dependency, and Kotlin implementation hierarchy must not stand in for
plugin application evidence.

Issue #342 will complete capability-to-command registration. #339 adds Clap
callability tests for its own commands and leaves registry generalization to
that issue, avoiding a second hand-maintained capability catalog.

## Output budget

Compact relationship defaults use four records and must stay below 120 lines
and 1,500 `cl100k_base` tokens. Fixtures include at least 500 references, a
branching cyclic call graph, 250 implementations, a deep bidirectional type
hierarchy, and 503 source-impact nodes. Tests measure the public JSON result,
not a conveniently small backend fixture.

Verbose and explain fixtures remain bounded by the explicit limit. They are
not subject to the compact token ceiling, but a test proves that requesting
detail does not remove backend limit or offset parameters.

## End-to-end evidence

The public guide and packaged skill include one executable sequence that:

1. resolves a declaration exactly and captures FQ name, declaration file, and
   declaration start offset;
2. passes that anchored identity to references and callers to prove usage and
   entry points without overload re-resolution;
3. follows a returned page token without overlap; and
4. passes the same identity to impact and demonstrates the typed overload
   granularity degradation when the FQ name is not unique.

Smoke tests execute the sequence against a scripted backend and a real
temporary SQLite source index. They assert that no step invokes discovery,
raw public dispatch, text search, or an unbounded backend request.

## Test inventory

| Area | Required proof |
| --- | --- |
| Clap surface | Five relation commands are public; required declaration file/offset pairs parse; old symbol relation flags and their `symbol_lookup.rs` execution path are removed; raw aliases fail; invalid depths, limits, directions, anchors, and tokens fail before execution. |
| Identity composition | Exact symbol output feeds every anchored command; overload fixtures select by file/start offset; not-found and identity-mismatch outcomes do not navigate or discover. |
| References | The #337 source/evidence/returned cursor round-trips losslessly; two deterministic pages do not overlap; `EXACT` versus `KNOWN_MINIMUM` remains truthful; declaration inclusion is explicit; containing symbol and `usageSiteScope` are non-conflicting; 500-reference compact output meets budget. |
| Callers/callees | Incoming and outgoing commands cannot be confused; depth, emitted limit, cursor, and candidate-visit budget reach the backend; provider visits stay bounded; cycles, timeouts, truncation, containing symbols, and continuation remain visible. |
| Implementations | Interfaces, abstract classes, and subclasses return typed implementation records; class-index provider visits stay bounded; deterministic pages and unavailable capability are distinct from an empty exhaustive result. |
| Hierarchy | Supertype, subtype, and both directions are exhaustive in Clap; depth, ordering, cycles, emitted limits, candidate visits, and degraded capability are typed. |
| Impact | Unique subjects get exact total count plus `LIMIT limit + 1 OFFSET offset`; overloads degrade without aggregate masquerading; 503-node pages do not overlap; unavailable/incompatible index degrades without false empty success. |
| Projection | Wrong-family records, inconsistent counts, and invalid page claims fail closed; compact/fields/count/verbose/explain retain their closed contracts. |
| Contracts | Catalog, generated request schemas, OpenAPI, API docs, examples, and capability expectations regenerate from source owners. |
| Guidance | Docs and packaged skill show identity reuse and semantic evidence consumption without text-search or raw-RPC fallback. |

## Non-goals

This issue does not add workspace file discovery, Gradle task/plugin graph
navigation, a generic graph query language, unchecked public positions, an
opaque server session handle, arbitrary field filters, or snapshot transactions
across source edits. The public declaration anchor is compiler-returned identity
evidence, not a user-selected raw RPC position. This issue does not generalize
the complete public capability registry owned by #342.
