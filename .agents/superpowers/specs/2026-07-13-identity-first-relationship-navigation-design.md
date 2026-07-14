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

Landed issue #337 makes the combined symbol result compact, adds real reference
paging to the backend, bounds call hierarchy work, and gives source impact a
separate count plus bounded SQL fetch. It also establishes
`ResultCardinality.EXACT|KNOWN_MINIMUM` and an opaque server-held reference
cursor bound to normalized query, selected evidence source, semantic
generation, provider position, and returned-before proof. #339 wraps that
handle without serializing any of those fields into Rust. #337 intentionally
does not create standalone relationship commands, containing-symbol identity,
deterministic continuation for every family, or typed degraded outcomes.

The #338 stack is an integration prerequisite, not relationship evidence. It
promotes the generic ownership-safe `ServerHeldContinuationStore` into
`analysis-api`, establishes `RunningAnalysisServer` as the single backend close
owner, adds the Rust projection/capability and generated-catalog layout, and
locks `tiktoken-rs` 0.12 as a test-only dependency. #339 rebases after #355,
#356, and #357 land, composes those foundations, and keeps workspace inventory
out of semantic relationship resolution.

The landed #337 exact symbol projection still keeps `fqName`/`kind` under `identity`
while declaration file and offset live in a sibling location. #339 closes that
composition gap: one reusable identity object carries the complete anchored
selector, and tests consume the emitted object rather than copying hard-coded
fixture offsets. An indexed fallback that lacks a trustworthy declaration
offset returns `IDENTITY_ANCHOR_UNAVAILABLE` instead of a partial `RESOLVED`
identity. This explicitly supersedes ADR 0016's exact-success payload and
fallback eligibility: indexed exact may resolve only one hard-filtered
candidate with one canonical file and one non-negative start offset.

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

The agent copies the single emitted `identity` object: `fqName`, `kind`,
`declarationFile`, `declarationStartOffset`, and optional `containingType`.
Under explicit `--workspace-root`, `--declaration-file` accepts the #341
workspace-relative spelling shown above and normalizes it into the same
canonical selector path that results report. Machine composition may pass the
reported canonical file unchanged; the example uses its shorter equivalent
relative spelling. A relation command resolves the
anchor directly, verifies the identity, and never falls back to discovery. Its
compact result begins at the relationship, not at the lookup request.

Impact is honest about the production source-index schema. The `declarations`
primary key collapses same-FQ declarations in one file, so an FQ-row count
cannot prove callable overload uniqueness. Functions and properties therefore
return `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`. Classes, interfaces, and
objects proceed only after compiler position verification and an exact
production row match on FQ name, canonical path, non-null declaration offset,
and kind. No FQ-wide edge is labeled as evidence for one selected callable.

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
Its `krp1` prefix carries the relation family, a SHA-256 fingerprint of the
canonical root plus complete anchored selector, declaration-inclusion choice,
direction, depth, and page limit, followed by one typed payload:

- references carry only #337's opaque backend-issued `ReferencePageToken`;
- call, implementation, and hierarchy pages carry a backend-issued
  `RelationTraversalHandle`; and
- impact carries a validated SQLite offset.

Both semantic handles are opaque backend identifiers. #337's
`ReferencePageToken` keeps its canonical UUID syntax unchanged; the traversal
handle owns its own URL-safe syntax. Only the enclosing Rust token is versioned.
Reference source/counters/query/generation and traversal frontiers are never
serialized into the public token, so no base64 codec or new Rust dependency is
needed. Decoding checks the outer version, relation, fingerprint, payload tag,
the applicable opaque-handle syntax, and the 10,000 impact-offset ceiling
before a runtime session or SQLite connection is opened.

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

The verified subject kind is a second typed admission boundary. Each response
root has a closed `UNSUPPORTED_SUBJECT_KIND` variant carrying the selector and
verified subject. The backend checks it after anchor verification and before
provider or index work. References admit class, interface, object, function,
property, and parameter; callers/callees admit function; implementations admit
class and interface; hierarchy admits class, interface, and object; impact
admits class, interface, object, function, and property. `UNKNOWN` is never
admitted. A mismatched optional kind assertion remains identity mismatch, not
unsupported-kind. A full command-by-`SymbolKind` matrix proves every rejected
pair performs zero provider/index work.

## Kotlin contract and backend flow

The internal typed symbol methods remain the bridge from canonical identity to
compiler position, but semantic continuation is owned entirely by the runtime
backend:

1. Validate the skill request, anchored selector, positive limit, and opaque
   reference or traversal handle at the transport boundary.
2. Check the required backend capability and delegate the complete typed query;
   `analysis-server` does not load continuation state or issue a preliminary
   resolve call.
3. Enter one backend `timedReadAction`, read the current
   `PsiModificationTracker.modificationCount`, and load/validate any handle's
   family, normalized query, selected source, subject, and generation.
4. Resolve the declaration file/start offset directly in that read action and
   compare canonical FQ name, kind, containing type, file, and offset. No
   declaration returns subject-not-found; a different declaration returns
   identity-mismatch with a non-null actual identity.
5. Select the reference source or restore the bound provider state, then pass
   the remaining candidate/state budget to the provider before it materializes
   evidence.
6. Atomically commit the next pure-data state with the same generation before
   leaving the read action. Never retain PSI or an analysis-session object.
7. Drop the extra proof record and return typed page, visited-candidate, and
   #337 cardinality evidence. A next handle requires
   `KNOWN_MINIMUM >= returnedBefore + returnedCount + 1`.

`symbol/references` and `symbol/callers` are extended to this contract.
`symbol/implementations` and `symbol/hierarchy` are added as internal typed
methods so the Rust public surface never constructs a raw `FilePosition`.
`callers` and `callees` use `symbol/callers` with fixed incoming and outgoing
directions respectively.

References migrate completely: `KastReferencesRequest`,
`KastReferencesQuery`, and every `KastReferencesResponse` variant carry the
anchored selector or verified subject. The orchestrator resolves the supplied
file/offset directly and does not call `resolveNamedSymbol`. The scaffold
contract and backend contract fixtures consume `ReferenceOccurrence` so this
migration cannot be bypassed by adapting occurrences back to bare locations.
`KastScaffoldReferences` is extracted from `SkillContracts.kt` into
`KastScaffoldReferences.kt` so materially edited production code continues to
meet the repository's one-top-level-type-per-file rule.

`RelationshipContinuationStore` is the one semantic relationship-state owner
inside `backend-idea`. It adapts #338's generic `analysis-api`
`ServerHeldContinuationStore` and stores sealed
reference/call/implementation/hierarchy state behind #337
`ReferencePageToken` or `RelationTraversalHandle` values. The sealed state
extends `ContinuationOwnedState`, output extends `ContinuationProjection`, and
the shared store alone owns single-use consume/reissue, TTL, capacity,
disposal, and close. Relationship handles use the landed typed `ServerLimits`
TTL/capacity (currently 60 seconds and 256 entries per typed store by default),
and one state contains at most 16,384 candidate, frontier, visited, and
provider-continuation entries. The adapter binds handle, relation family,
normalized query fingerprint, selected subject, reference source where
applicable, private returned-before count, and semantic generation. Because
#337's UUID has no verifiable issuer epoch, absent is
always cursor-invalid `UNKNOWN_HANDLE`: backend-A token after restart in fresh
backend B, a never-issued random canonical UUID, consumed replay, and capacity
eviction are intentionally indistinguishable and all perform zero provider
work. Cursor-stale requires positive evidence from consumed retained state
whose generation changed, or the shared store's typed `ExpiredToken` result.
The traversal handle's typed family prefix rejects wrong-family use before
store/provider work; the shared store's terminal `QueryMismatch` maps to the
family's invalid `QUERY_MISMATCH`. Malformed handle syntax fails request
validation. Reaching the state-entry ceiling returns the owning family's typed
state-budget reason.
`AnalysisBackend` carries handles in its queries/results;
`ObservedAnalysisBackend` overrides and delegates every changed method.
`analysis-server` remains transport-only. The shared store retains #337's
one-shot consumption rule: `Complete` disposes and `Reissue` atomically moves
the same owned state behind a fresh handle. Expiry, eviction, mismatch,
failure, terminal consumption, backend close, and server shutdown dispose the
state exactly once. `RunningAnalysisServer` remains the single backend close
owner. The backend invokes consume/reissue inside the relationship
`timedReadAction`, so generation validation, provider work, state mutation, and
next-token publication are atomic with respect to PSI writes. A consumed token
cannot be replayed to repeat or fork provider work.

INDEX references query production `symbol_references` by FQ name, canonical
target path, and the selected non-null `target_offset`. Every emitted row must
repeat the same exact target anchor. Null or conflicting target anchors return
`INDEX_IDENTITY_UNAVAILABLE`; FQ-only rows are never filtered after the fact as
if they were overload-safe. A first page may fall back to IDEA before a cursor
exists. An INDEX-bound continuation may return `BOUND_SOURCE_UNAVAILABLE` but
never switch to IDEA. Fixtures force two same-FQ target offsets, select the
second, and cover exact INDEX, unsafe-INDEX-to-IDEA first-page fallback, and
INDEX-continuation refusal to switch sources.

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

data class SymbolIdentity(
    val fqName: String,
    val kind: SymbolKind,
    val declarationFile: NormalizedPath,
    val declarationStartOffset: NonNegativeInt,
    val containingType: String? = null,
)
```

Direct sealed variants stay with the root as allowed by the one-type-per-file
rule. `SymbolIdentity` is the one lightweight reusable compiler identity; its
canonical file and offset are the same public anchor exact lookup emits. It
does not include documentation, preview, or declaration source. Relation
records carry a separate compact `Location` when range or line/column evidence
is needed. `ReferenceOccurrence.containingSymbol` is the semantic
identity proof. The existing #337 `Location.usageSiteScope` remains an
optional structural range/source-text field controlled by
`includeUsageSiteScope`; neither field substitutes for the other. The IDEA
references implementation obtains both requested forms while it owns PSI for
the bounded occurrence page. It does not create an N+1 Rust lookup loop or
scan every relation item merely to populate compact output.

Call hierarchy keeps its recursive full-fidelity result internally. The
backend engine flattens edges breadth-first using provider-stable canonical
call-site order; it does not globally sort an unseen child set by related
identity. Frontier parents sort by canonical identity, then each parent emits
edges by canonical call-site file/start/end offsets and related identity only
as the final tie-breaker. The emitted window is at most `limit + 1`, but
provider work is bounded before materialization. The concrete IDEA strategy is:

- `IdeaBoundedReferenceProvider` streams paths with
  `FileTypeIndex.processFiles`, admits at most the candidate/state cap plus one,
  and stops. Cap-plus-one proves overflow and returns a family budget outcome
  with no records or retained partial snapshot. At or below the cap it sorts
  the complete bounded path buffer and scans each file with the existing
  `PsiReferenceScanner` in lexical offset order. References and incoming calls
  share this provider plan.
- outgoing calls use `IdeaOutgoingLexicalDfsProvider`, a resumable lexical
  depth-first walk of the selected declaration body. Its pure-data state is a
  bounded root-to-current child-index stack plus next reference index. It
  traverses nested blocks, local property initializers, and lambda bodies.
  Lambda calls belong to the enclosing named callable because no navigable
  lambda identity exists. It skips nested named functions, classes, objects,
  and accessors. Resume rehydrates the stack only under the
  unchanged-generation read action and continues at the exact next reference;
  exhaustive cardinality requires exhausting lambda bodies too. References at
  one identical call-site range are the only locally sorted tie group; they
  sort by related identity under the existing candidate/state bound, and an
  overflowing group degrades without emitting or retaining a partial group;
  and
- `IdeaBoundedInheritorProvider` uses
  `ClassInheritorsSearch.search(..., checkDeep = false).forEach`, stops at the
  direct-inheritor cap plus one, and applies the same no-partial-page overflow
  rule. At or below the cap it canonicalizes anchors in the same read action,
  then sorts the complete admitted buffer.

No provider calls `FileTypeIndex.getFiles(...).toList()`,
`ReferencesSearch.findAll`, or inheritor `findAll()`. Crossing the cap returns a
family-specific candidate-budget outcome instead of a partial deterministic
page; no sorted prefix is observable. The bounded snapshot providers return
`visitedCandidateCount`, consumed evidence, next provider state, and
exhaustiveness only for an admitted complete snapshot. The outgoing provider
returns the same evidence with its bounded lexical DFS stack. The backend-owned
engine updates the frontier and visited identities atomically. Tests tripwire
the forbidden APIs, instrument visits, exercise cap and cap-plus-one inputs,
assert overflow has no records/page claim/state, and force page breaks inside
one file, frontier parent, and provider stream. Outgoing fixtures additionally
prove nested blocks and local property initializers are included, nested
named callable/type/accessor bodies are excluded, lambda bodies are included,
and resumption around those boundaries neither replays nor skips an owned call.
Related names deliberately appear in reverse lexical order across call sites;
pages remain globally ordered by call-site offset and do not overlap. A named
function whose only callee is inside a lambda is non-empty, and another page
break inside a lambda resumes the exact next call.

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

Impact reports an exact total only for a compiler-verified non-callable subject
whose production declaration row matches FQ name, canonical path, non-null
offset, and kind. Functions and properties degrade because the current primary
key cannot prove same-file overload isolation. References report exact
cardinality only after exhaustive exact-anchor INDEX or IDEA traversal;
bounded work remains `KNOWN_MINIMUM`.
Every semantic family reports `EXACT` only after exhaustion and otherwise
reports a known minimum at least as large as private
`returnedBefore + returnedCount + 1` when another record is proved. The
backend page factory enforces that private cumulative invariant. Rust cannot
reconstruct returned-before from the opaque handle; it rejects exposed count
inconsistency, `KNOWN_MINIMUM < returnedCount + 1`, false exact totals,
truncation without a token, a token without truncation, visited-candidate claims
above the request budget, and relation items of the wrong family.

## Paging and impact

The first request starts reference paging without a cursor, starts a new
traversal state, or uses impact offset zero according to family. A successful
non-final page emits the next query-bound token. Reusing a reference or impact
token passes its opaque handle or typed SQLite offset unchanged. Reusing a
semantic handle asks the runtime backend to load state; the current command
must match both the Rust fingerprint and backend-held query proof. A reference
cursor privately pins `INDEX` or `IDEA`; if that source disappears, the command
returns the references-family `BOUND_SOURCE_UNAVAILABLE` rather than restarting
from another source.

Issue #337 already gives references an opaque server-held cursor and makes the
SQLite impact query count separately and fetch only `limit + 1` rows. This
issue wraps the landed reference handle without replacing or decoding it. It
extends the Rust metrics request with a validated impact offset and changes the
impact row query to `LIMIT limit + 1 OFFSET offset`. The exact impact-node count
stays independent. Before opening the impact row query, Rust resolves the
normalized declaration file/start offset through the compiler position
endpoint and compares the complete returned identity. It then reads the
production declaration row by FQ name, canonical file, and declaration offset.
Functions and properties degrade without reading aggregate impact rows because
the production primary key cannot prove same-file overload uniqueness. A
production `SqliteSourceIndexStore` regression indexes three same-file
overloads, selects the third compiler anchor, demonstrates that declaration-row
counting cannot observe three, and proves no aggregate impact-row query is
issued. Non-callable exact-row matches retain ordered depth/source/target/kind
paging, so two pages in an unchanged index have no overlap.

## Error and degradation model

Expected anchored relationship outcomes are `AVAILABLE`, `SUBJECT_NOT_FOUND`,
`SUBJECT_IDENTITY_MISMATCH`, and `UNSUPPORTED_SUBJECT_KIND`.
`SUBJECT_AMBIGUOUS` remains confined to exact
lookup and internal compatibility requests; it is not a public anchored
relationship response. Mismatch carries a non-null actual `SymbolIdentity`;
absence at the anchor is not encoded as `actual = null`. Capability, provider,
or index absence is `DEGRADED`, with a closed reason enum owned by that response
family. A degraded result preserves the exact selector and verified subject;
it never returns an empty page that looks exhaustive. Each response family
also owns its typed cursor-stale and cursor-invalid variants.

The closed stale reasons are `GENERATION_CHANGED` and `EXPIRED`; `EXPIRED` is
legal only from the shared store's typed `ExpiredToken` result for a retained
entry. The closed invalid reasons are
`UNKNOWN_HANDLE`, `FAMILY_MISMATCH`, and `QUERY_MISMATCH`. A syntactically
malformed handle does not enter these outcomes. This taxonomy deliberately
makes restart-to-fresh-backend and random canonical UUID identical instead of
guessing issuer history that #337's token does not encode.

There is no cross-family `RelationDegradedCode`. References own
`REFERENCES_UNAVAILABLE`, `INDEX_IDENTITY_UNAVAILABLE`,
`BOUND_SOURCE_UNAVAILABLE`, and `CANDIDATE_BUDGET_REACHED`. Calls own call
capability, candidate-budget, state-budget, and timeout reasons.
Implementations and hierarchy own parallel enums with their own capability
value. Impact alone owns `SOURCE_INDEX_UNAVAILABLE`,
`IMPACT_INDEX_IDENTITY_UNAVAILABLE`, and
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`.

An unavailable or exact-anchor-unsafe reference index is not degraded when a
first page can choose IDEA. A continuation cannot change its backend-held
reference source. A callable passed to FQ-keyed impact becomes the impact-only
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`; no aggregate records are returned as
if they belonged only to that overload.

Malformed backend results, transport failures after capability admission,
public query-token mismatches, and exact-root admission failures remain
structured errors with closed codes. Relationship response roots do not add a
primitive `Failure(code: String)` escape hatch. This prevents degradation or a
stringly result variant from hiding a broken backend.

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

Scoped `AGENTS.md` files record the host-agnostic handle model,
transport-only server boundary, backend-owned atomic continuation state,
production index identity limitations, and Rust command/projection gates.
Opaque server handles and the impact ASCII offset use existing `sha2`/`hex`
support, so no runtime token codec is added. The exact compact-output gate does
reuse #338's `tiktoken-rs = "0.12"` Rust dev-dependency and corresponding
`Cargo.lock` entries; #339 does not create a dependency diff.

## Output budget

Compact relationship defaults use four records and must stay at or below 120
lines and 1,500 `cl100k_base` tokens. Fixtures include at least 500 references, a
branching cyclic call graph, 250 implementations, a deep bidirectional type
hierarchy, and 503 source-impact nodes. Tests measure the public JSON result,
not a conveniently small backend fixture. The integration test imports
`tiktoken_rs::cl100k_base`, constructs the tokenizer with a test failure on
initialization error, and asserts the encoded rendered JSON length directly;
the gate therefore compiles and runs under the locked Cargo graph.

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
   granularity degradation for a callable the production declaration key
   cannot isolate.

Smoke tests execute the sequence against a scripted backend and a real
temporary SQLite source index. They assert that no step invokes discovery,
raw public dispatch, text search, or an unbounded backend request.

## Test inventory

| Area | Required proof |
| --- | --- |
| Clap surface | Five relation commands are public; required declaration file/offset pairs parse; old symbol relation flags and their `symbol_lookup.rs` execution path are removed; raw aliases fail; invalid depths, limits, directions, anchors, and tokens fail before execution. |
| Identity composition | ADR 0016 exact success/fallback is superseded explicitly; one exact symbol `identity` object feeds every anchored command unchanged; same-file overload fixtures select by file/start offset; anchor-unavailable/not-found/non-null mismatch outcomes do not navigate or discover. |
| Subject-kind admission | Every command-kind pair follows the closed matrix; unsupported pairs return `UNSUPPORTED_SUBJECT_KIND` with selector/verified subject and zero provider/index work. |
| References | Anchored `KastReferences*` contracts never call named resolution; `KastScaffoldReferences.kt` and fixtures consume occurrences; #337's opaque cursor round-trips without serialized source/counters; target path/offset INDEX reads isolate a forced overload; unsafe first-page INDEX falls back to IDEA while an INDEX-bound continuation never switches; deterministic pages do not overlap; continuation proves the plus-one known minimum; containing symbol and `usageSiteScope` are non-conflicting. |
| Atomic backend state | `backend-idea`'s relationship adapter is the only semantic owner and composes #338's generic `analysis-api` store; generation check, provider work, state mutation, and shared-store reissue occur in one read action; complete/reissue/disposal/backend-close semantics are proved; queued-write races reject stale state; backend-A token after restart-to-B and random UUID are identically absent/invalid with zero work, while retained generation mismatch is stale; `ObservedAnalysisBackend` delegates each handle-bearing method once; `analysis-server` owns no duplicate semantic store. |
| Callers/callees | Incoming and outgoing commands cannot be confused; depth, emitted limit, state handle, and candidate-visit budget reach the backend; bounded incoming search preserves frontier/provider state; outgoing lexical DFS includes nested blocks/local initializers/lambdas, excludes nested named callable/type/accessor bodies, and resumes its pure child-index/reference stack without overlap; reverse-lexical related names prove provider-stable call-site ordering across pages; lambda-only callees and lambda page breaks remain visible; cap-plus-one and forbidden materializer tripwires prove bounded work. |
| Implementations | Interfaces, abstract classes, and subclasses return typed implementation records; bounded `ClassInheritorsSearch.forEach` provider visits stay bounded; backend state resumes without overlap; stale/invalid handles and unavailable capability are distinct from an empty exhaustive result. |
| Hierarchy | Supertypes/subtypes/both are exhaustive in Clap; depth, ordering, cycles, emitted limits, stateful frontiers, candidate visits, generation staleness, and degraded capability are typed. |
| Impact | Compiler position lookup verifies the anchor; production row path/offset/kind identity gates non-callable impact; a production-store same-file overload regression proves FQ row counts cannot authorize callable aggregate edges; exact subjects retain total count plus `LIMIT limit + 1 OFFSET offset`; unavailable/incompatible index degrades without false empty success. |
| Projection | Wrong-family records, degraded reasons, or unsupported-kind variants, nullable mismatch actual, exposed count inconsistency, omitted subjects/selectors, primitive failure codes, and invalid page claims fail closed; backend state tests separately enforce cumulative plus-one proof; compact/fields/count/verbose/explain retain their closed contracts. |
| Contracts | Catalog, generated request schemas, OpenAPI, API docs, examples, and capability expectations regenerate from source owners. |
| Output budget | Locked `tiktoken-rs::cl100k_base()` encoding proves every compact high-cardinality public fixture stays within 120 lines and 1,500 tokens. |
| Guidance | Docs and packaged skill show identity reuse and semantic evidence consumption without text-search or raw-RPC fallback. |

## Non-goals

This issue does not add workspace file discovery, Gradle task/plugin graph
navigation, a generic graph query language, unchecked public positions, an
opaque server handle as subject identity, arbitrary field filters, or snapshot
transactions across source edits. The bounded reference/traversal handles
represent only backend-owned continuation state and become stale when semantic
generation changes. The public declaration anchor remains compiler-returned
identity evidence, not a user-selected raw RPC position. This issue does not
change the production source-index primary key or generalize the complete
public capability registry owned by #342.
