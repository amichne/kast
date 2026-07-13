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
separate count plus bounded SQL fetch. That fixes the catastrophic output
shape, but it intentionally does not create standalone relationship commands,
containing-symbol evidence, deterministic continuation for every family, or
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

An opaque symbol-handle format was also considered. It would make overloads
copyable in one field, but it would embed source locations that become stale
after edits or require server-side session storage. The existing exact
identity contract already returns canonical FQ name, kind, file, and
containing declaration. Passing those values through a validated selector is
portable across processes and worktrees and preserves explicit ambiguity.

## Public workflow

The normal sequence is:

```console
kast agent symbol \
  --query OrderService \
  --fields identity,location \
  --workspace-root "$PWD"

kast agent references \
  --symbol com.example.OrderService \
  --workspace-root "$PWD"

kast agent callers \
  --symbol com.example.OrderService.submit \
  --depth 2 \
  --workspace-root "$PWD"

kast agent impact \
  --symbol com.example.OrderService \
  --depth 2 \
  --workspace-root "$PWD"
```

The agent copies `identity.fqName` into `--symbol`. If exact lookup returned
disambiguating `kind`, location, or containing type, it supplies those as hard
selector flags. A relation command resolves only exact identity and never
falls back to discovery. Its compact result begins at the relationship, not at
the lookup request.

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
    kind: Option<AgentSymbolKind>,
    file_hint: Option<WorkspaceFileHint>,
    containing_type: Option<CanonicalSymbolName>,
}

struct AgentRelationLimit(NonZeroU8);       // 1..=200, default 4
struct AgentRelationDepth(NonZeroU8);       // 1..=8, default 1
struct AgentRelationPageOffset(u16);        // 0..=10_000, internal
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
canonical root plus normalized selector, declaration-inclusion choice,
direction, depth, and page limit, and the next offset. Decoding checks the
version, relation, fingerprint, and `0..=10_000` range before a runtime session
or SQLite connection is opened.

The concrete Clap commands are:

```text
references       selector + include-declaration + limit + page-token + view
callers          selector + depth + limit + page-token + view
callees          selector + depth + limit + page-token + view
implementations  selector + limit + page-token + view
hierarchy        selector + direction + depth + limit + page-token + view
impact           symbol + depth + limit + page-token + view
```

All relationship view families support ADR 0020 compact, fields, count,
verbose, and explain modes. Detailed modes expose full validated backend
evidence for the requested page only; they do not expand the result budget.

## Kotlin contract and backend flow

The internal typed symbol methods remain the bridge from canonical identity to
compiler position:

1. Validate the skill request and positive limit/internal offset.
2. Resolve the subject with the ADR 0016 exact resolver.
3. Return typed subject-not-found or subject-ambiguous outcomes without
   selecting a candidate.
4. Check the required backend capability.
5. Convert the compiler-owned declaration location to the raw backend query.
6. Collect only `offset + limit + 1` deterministically ordered records.
7. Drop the extra record and return typed page and count evidence.

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
or declaration source. The IDEA references implementation obtains the nearest
semantic containing declaration while it owns PSI. It does not create an N+1
Rust lookup loop.

Call hierarchy keeps its recursive full-fidelity result internally. The skill
orchestrator flattens edges breadth-first into caller or callee records after
the backend engine has sorted each child set by canonical identity and
location. It requests at most `offset + limit + 1` edges. Type hierarchy and
implementation searches use the same bounded ordered window. Cycle, timeout,
max-depth, and backend-limit evidence survives as typed limitations.

## Rust result model

Every compact success returns the subject once and a closed item family. The
shared page model is:

```rust
struct AgentRelationPage {
    known_count: usize,
    count_kind: AgentRelationCountKind, // Exact | LowerBound
    returned_count: usize,
    truncated: bool,
    next_page_token: Option<AgentRelationPageToken>,
}
```

Reference records contain `REFERENCE`, a location, and `Known`, `TopLevel`, or
`Unavailable` containing-symbol evidence. Call records contain `CALLER` or
`CALLEE`, the related identity, call-site location, traversal depth, and the
containing identity. Implementation and hierarchy records contain their
specific relation kind, identity, declaration location, and hierarchy depth
where applicable. These are Rust enum variants, not one struct full of
optional fields.

References and impact report exact total counts. Calls, implementations, and
hierarchy report an exact count only after exhaustion; otherwise they report a
lower bound at least as large as `offset + returnedCount + 1`. The projection
rejects inconsistent returned counts, false exact totals, truncation without a
token, a token without truncation, and relation items of the wrong family.

## Paging and impact

The first request has offset zero. A successful non-final page emits the next
query-bound token. Reusing the token reconstructs only the internal offset;
all other query values still come from the current typed command and must
match the fingerprint.

Issue #337 already gives references an internal canonical offset and makes
the SQLite impact query count separately and fetch only `limit + 1` rows.
This issue extends the Rust metrics request with a validated offset and changes
the impact row query to `LIMIT limit + 1 OFFSET offset`. The exact count query
stays independent. Ordered rows use depth, source path, target FQ name, and
edge kind, so two pages in an unchanged index have no overlap.

## Error and degradation model

Expected identity outcomes are `AVAILABLE`, `SUBJECT_NOT_FOUND`, and
`SUBJECT_AMBIGUOUS`. Capability or index absence is `DEGRADED`, with a closed
typed limitation-code enum and the requested capability. A degraded result
never returns an empty page that looks exhaustive.

Only known availability conditions degrade:

- missing `FIND_REFERENCES` becomes `REFERENCE_INDEX_UNAVAILABLE`;
- missing `CALL_HIERARCHY` becomes `CALL_HIERARCHY_UNAVAILABLE`;
- missing `IMPLEMENTATIONS` becomes `IMPLEMENTATIONS_UNAVAILABLE`;
- missing `TYPE_HIERARCHY` becomes `TYPE_HIERARCHY_UNAVAILABLE`; and
- an unavailable or incompatible source-index database becomes
  `SOURCE_INDEX_UNAVAILABLE`.

Malformed backend results, transport failures after capability admission,
query-token mismatches, and exact-root admission failures remain errors. This
prevents degradation from hiding a broken backend.

## Issue boundaries

Issue #338's workspace inventory is not a relation data source. Its public
`filePath` may be copied into `--file-hint`, but #339 neither edits the
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

1. resolves a declaration exactly and captures `identity.fqName`;
2. passes that identity to references and callers to prove usage and entry
   points;
3. follows a returned page token without overlap; and
4. passes the same identity to impact to estimate indexed change reach.

Smoke tests execute the sequence against a scripted backend and a real
temporary SQLite source index. They assert that no step invokes discovery,
raw public dispatch, text search, or an unbounded backend request.

## Test inventory

| Area | Required proof |
| --- | --- |
| Clap surface | Five relation commands are public; old symbol relation flags and raw aliases fail; invalid depths, limits, directions, and tokens fail before execution. |
| Identity composition | Exact symbol output feeds every command; canonical selectors reach the backend; not-found and ambiguous outcomes do not navigate or discover. |
| References | Two deterministic pages do not overlap; declaration inclusion is explicit; containing symbol is known, top-level, or unavailable; 500-reference compact output meets budget. |
| Callers/callees | Incoming and outgoing commands cannot be confused; depth and total budget reach the backend; cycles, timeouts, truncation, containing symbols, and continuation remain visible. |
| Implementations | Interfaces, abstract classes, and subclasses return typed implementation records; deterministic pages and unavailable capability are distinct from an empty exhaustive result. |
| Hierarchy | Supertype, subtype, and both directions are exhaustive in Clap; depth, ordering, cycles, limits, and degraded capability are typed. |
| Impact | Exact total count plus `LIMIT limit + 1 OFFSET offset`; 503-node pages do not overlap; unavailable/incompatible index degrades without false empty success. |
| Projection | Wrong-family records, inconsistent counts, and invalid page claims fail closed; compact/fields/count/verbose/explain retain their closed contracts. |
| Contracts | Catalog, generated request schemas, OpenAPI, API docs, examples, and capability expectations regenerate from source owners. |
| Guidance | Docs and packaged skill show identity reuse and semantic evidence consumption without text-search or raw-RPC fallback. |

## Non-goals

This issue does not add workspace file discovery, Gradle task/plugin graph
navigation, a generic graph query language, raw public positions, an opaque
server session handle, arbitrary field filters, or snapshot transactions
across source edits. It does not generalize the complete public capability
registry owned by #342.
