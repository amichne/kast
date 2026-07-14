# ADR 0022: Identity-first relationship navigation

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 only for the public relationship-navigation
surface. It supersedes ADR 0016 for the exact-success payload, indexed-fallback
eligibility, and the one-shot relationship flags on `kast agent symbol`.
Specifically, exact `RESOLVED` now requires one reusable declaration anchor and
indexed exact fallback may succeed only when it proves that anchor. It extends
ADR 0020's compact projection and bounded-work rules. Issue #337 must land
before this decision is implemented because its reference paging,
relationship budgets, impact counts, and projection types are the starting
contract. #339 consumes #337's opaque server-held `ReferencePageToken` and
`ResultCardinality` unchanged; it does not serialize the cursor's source,
position, count, query, or semantic generation into the public token.

## Decision

Kast exposes five standalone typed relationship commands:

```console
kast agent references \
  --symbol <fq-name> \
  --declaration-file <workspace-relative-path> \
  --declaration-start-offset <offset> \
  --workspace-root <root>

kast agent callers \
  --symbol <fq-name> \
  --declaration-file <workspace-relative-path> \
  --declaration-start-offset <offset> \
  --workspace-root <root>

kast agent callees \
  --symbol <fq-name> \
  --declaration-file <workspace-relative-path> \
  --declaration-start-offset <offset> \
  --workspace-root <root>

kast agent implementations \
  --symbol <fq-name> \
  --declaration-file <workspace-relative-path> \
  --declaration-start-offset <offset> \
  --workspace-root <root>

kast agent hierarchy \
  --symbol <fq-name> \
  --declaration-file <workspace-relative-path> \
  --declaration-start-offset <offset> \
  --direction supertypes|subtypes|both \
  --workspace-root <root>
```

`kast agent impact` accepts the same anchored selector and remains the
source-index relationship command. Exact `kast agent symbol` lookup returns one
reusable `identity` object containing `fqName`, `kind`, canonical
`declarationFile`, non-negative `declarationStartOffset`, and optional
`containingType`. The six relationship commands consume those fields directly.
`--kind` and `--containing-type` remain optional hard assertions;
`--declaration-file` and `--declaration-start-offset` are a required pair. The
commands never accept discovery mode and never run lexical or fuzzy discovery
implicitly.

The relationship commands replace `kast agent symbol --references`,
`--callers`, and `--caller-depth`. Symbol lookup returns identity; a relation
command consumes it. This keeps lookup evidence out of repeated relationship
responses and makes every relationship capability independently callable.
The internal `symbol/references` and `symbol/callers` RPC methods remain
implementation details.

Each command owns typed arguments instead of a generic relation name:

- `references` accepts `--include-declaration`, `--limit`, and `--page-token`;
- `callers` and `callees` accept `--depth`, `--limit`, and `--page-token`;
- `implementations` accepts `--limit` and `--page-token`;
- `hierarchy` accepts a required typed `--direction`, `--depth`, `--limit`, and
  `--page-token`; and
- `impact` keeps `--depth` and `--limit` and adds `--page-token`.

Relationship limits default to four records and accept 1 through 200. Call
and type hierarchy depth defaults to one and accepts 1 through 8. Explicit
verbose or explain output never removes these work limits. Out-of-range
budgets, an empty identity, malformed tokens, and a token issued for another
relation or query fail at the typed command boundary.

## Identity contract

The public reusable identity remains a compiler identity defined from ADR
0016, not a session handle, but FQ name alone is not exact for overloads. The
selector therefore carries canonical FQ name, normalized canonical declaration
file, and non-negative declaration start offset. The CLI accepts #341
workspace-relative declaration paths under explicit `--workspace-root` and
stores/reports their canonical spelling. Optional kind and containing type are
assertions copied from exact lookup. File and offset identify the resolved
declaration; the backend resolves that anchor and verifies every supplied
identity field before relationship work. It never searches by FQ name and then
chooses an overload.

Exact lookup may report `RESOLVED` only when it can emit that complete anchor.
This is the explicit replacement for ADR 0016's exact-success shape. An indexed
fallback is eligible only after compiler unavailability and only when exactly
one indexed candidate satisfies every hard constraint and carries one
canonical declaration file plus one non-negative declaration start offset. A
missing, null, conflicting, or non-unique indexed anchor returns the fourth
closed exact outcome `IDENTITY_ANCHOR_UNAVAILABLE`; it must not emit a
reusable-looking partial identity or reinterpret that state as `NOT_FOUND`.

An absent anchor is a typed command-usage failure. A missing declaration is
`SUBJECT_NOT_FOUND`; an anchor that now resolves to another declaration is
`SUBJECT_IDENTITY_MISMATCH` and carries the non-null actual identity resolved at
that position. No declaration at the anchor is `SUBJECT_NOT_FOUND`, not a
mismatch with `actual = null`. `SUBJECT_AMBIGUOUS` remains an exact-lookup
outcome and may be preserved on internal compatibility requests, but a valid
anchored public selector cannot select ambiguously. Public callers copy the
typed declaration anchor returned by Kast; they do not invent unchecked raw
backend positions or arbitrary JSON.

`symbol/references`, `symbol/callers`, `symbol/implementations`, and
`symbol/hierarchy` all consume the anchored selector. The references endpoint
must not retain its former FQ-name-plus-hints request or call
`resolveNamedSymbol`; otherwise same-file overloads would re-enter ambiguous
name resolution after lookup already chose one declaration. Existing scaffold
composition migrates to `ReferenceOccurrence` and preserves containing-symbol
evidence rather than adapting the new reference result back to an untyped bare
location list. `KastScaffoldReferences` is extracted from `SkillContracts.kt`
into its own same-named file when materially edited.

Indexed references are exact only when the production `symbol_references`
target identity proves the selected anchor. The index query includes FQ name,
canonical target path, and the single selected `target_offset`; returned rows
must repeat that same non-null target path/offset. FQ-only rows, null target
anchors, or conflicting target anchors are not evidence for the selected
overload. On a first page the backend may reject unsafe INDEX evidence and use
IDEA instead. A continuation already bound to INDEX returns the typed
reference-family degradation `INDEX_IDENTITY_UNAVAILABLE` or
`BOUND_SOURCE_UNAVAILABLE`; it never switches to IDEA or emits FQ-aggregate
references. Tests force INDEX with two same-FQ offsets, prove only the selected
offset is returned, cover null/mixed anchors, and prove first-page INDEX-to-IDEA
fallback without continuation source switching.

The source index currently keys impact edges by FQ name, not declaration
anchor or callable signature. Its production `declarations` primary key is
`(fq_id, prefix_id, filename)`, so counting declaration rows cannot detect two
same-FQ overloads in one file. Impact must not use such a count as overload
proof. It first verifies the compiler anchor, then requires a production-schema
row whose FQ name, canonical file, non-null `declaration_offset`, and kind match
the subject. `FUNCTION` and `PROPERTY` subjects conservatively return
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` because the current schema cannot
prove callable or receiver overload isolation. `CLASS`, `INTERFACE`, and
`OBJECT` may use FQ-keyed impact only when that exact offset row matches and no
second stored declaration row shares the FQ name. The latter count rejects
additional stored declarations; it is not same-file overload proof. Missing or
mismatched offset identity returns `IMPACT_INDEX_IDENTITY_UNAVAILABLE`. A
regression using the production store indexes same-file overloads and proves
the collapsed declaration key can never authorize aggregate callable impact.

## Result contract

Default responses contain the subject identity once, the relation kind, at
most four typed records, bounded counts, page evidence, limitations, and the
schema version. They do not repeat the exact-lookup request, fuzzy ranking,
declaration documentation, surrounding source, or raw transport envelopes.

Record shapes are closed by family:

- references report `REFERENCE`, a compact location, and typed containing
  symbol evidence;
- callers and callees report `CALLER` or `CALLEE`, the related symbol, the
  call-site location, traversal depth, and containing symbol;
- implementations report `IMPLEMENTATION`, the implementation identity and
  declaration location;
- hierarchy reports `SUPERTYPE` or `SUBTYPE`, the related identity,
  declaration location, and traversal depth; and
- impact retains its typed source path, edge kind, depth, occurrence count,
  and confidence evidence from ADR 0020.

Containing-symbol evidence is `KNOWN`, `TOP_LEVEL`, or `UNAVAILABLE`; the
unavailable variant carries a closed reason enum, and a null field must not
conflate those states. Reference collection resolves the
containing declaration while it already owns compiler PSI. The Rust CLI must
not issue one follow-up request per reference.

Counts reuse #337's `ResultCardinality` wire contract exactly: `EXACT` or
`KNOWN_MINIMUM`. An exhausted deterministic traversal or an authoritative
exact index count can report `EXACT`. A bounded traversal that has proved only
another record reports `KNOWN_MINIMUM` and a continuation token. Whenever a
continuation exists, the known minimum is at least private
`returnedBefore + returnedCount + 1`; the extra proof record is not emitted.
No partial count may pose as the total relation count, and #339 does not rename
this contract to `LOWER_BOUND`.

## Paging and deterministic work

Every relationship family uses the same public page evidence:

- `returnedCount` is the number of records on this page;
- `cardinality` is lossless #337 `ResultCardinality.EXACT` or
  `ResultCardinality.KNOWN_MINIMUM`;
- `truncated` agrees with the existence of more known work; and
- `nextPageToken` is present exactly when another page is known; when present,
  `KNOWN_MINIMUM >= returnedBefore + returnedCount + 1`, using the private
  server-held returned-before count.

Public page tokens are opaque, versioned, and query-bound. The typed Rust token
includes the relation family and a fingerprint of the normalized workspace
root, complete anchored selector, declaration-inclusion choice,
direction/depth where applicable, and page limit. Passing a token to another
relation, subject, workspace, or budget returns
`RELATION_PAGE_TOKEN_MISMATCH` before backend work.

Reference tokens wrap only #337's opaque backend-issued `ReferencePageToken`
handle. Its bound source (`INDEX` or `IDEA`), provider position,
returned-before count, normalized query fingerprint, selected subject, and
semantic generation remain in backend-owned state and never appear in the Rust
token. Impact tokens alone carry a validated SQLite offset. These small typed
payloads use canonical ASCII fields and existing SHA-256/hex support; they do
not require a base64 dependency.

All compiler-backed relationship continuation is stateful and has one owner:
the runtime backend. `backend-idea` extends #337's bounded
`ServerHeldContinuationStore`; `analysis-server` transports handles and
does not own or reconstruct continuation state. The store contains a sealed
reference state plus family-specific call/implementation/hierarchy state. The
reference state holds #337's source and provider position; traversal state
holds the breadth-first frontier, visited identities, and per-provider
continuation. Every state also holds consumed evidence, returned-before proof,
normalized query fingerprint, selected subject, and semantic generation.
Handles have a 15-minute lifetime, at most 1,024 live entries per exact
workspace runtime, and at most 16,384 candidate/frontier/visited/provider
entries per state. Runtime restart, eviction, expiry, or generation change
returns a family-typed stale outcome; malformed, unknown, wrong-family, or
query-mismatched handles return a family-typed invalid outcome. No family may
restart from zero or silently select another subject. Continuation handles
retain #337's one-shot consumption rule: replaying a consumed handle is typed
invalid and cannot duplicate provider work.

IDEA performs handle lookup, query/source validation, reads
`PsiModificationTracker.modificationCount`, compares the stored generation,
runs target resolution and provider work, and commits the next pure-data state
inside one `timedReadAction`. The read lock makes generation validation and
provider work atomic with respect to PSI writes. State never retains PSI,
smart pointers, or analysis-session objects. `ObservedAnalysisBackend`
delegates every handle-bearing method without default-method fallback and
records the operation once. Tests queue a write between pages and during a
continuation read action to prove that old state is rejected before provider
work and that a write cannot slip between the generation check and state
commit.

For an unchanged admitted workspace, relation ordering is deterministic:

- references sort by file path, start offset, end offset, and containing
  symbol identity;
- call edges use breadth-first depth, parent identity, related identity, and
  call-site location;
- implementations and hierarchy nodes sort by fully-qualified name, kind,
  file path, and declaration offset; and
- impact nodes sort by depth, source path, target identity, and edge kind.

`limit + 1` is an emitted-record window, not by itself a backend-work bound.
The named IDEA strategy is bounded snapshot-then-sort. References and incoming
calls stream workspace Kotlin files through `FileTypeIndex.processFiles` into a
candidate-path buffer that admits at most the state/candidate cap plus one.
Exactly cap-plus-one proves overflow: the provider stops immediately and
returns a typed family budget outcome with no records, page claim, or retained
partial snapshot. At or below the cap, it sorts the complete bounded snapshot,
then scans each file in lexical offset order with `PsiReferenceScanner`.
Outgoing calls walk the selected declaration's direct PSI children in lexical
offset order. Implementations and direct subtypes use
`ClassInheritorsSearch.search(..., checkDeep = false).forEach` with the same
cap-plus-one admission rule, canonicalize anchors in the same read action, and
sort only a complete admitted snapshot. No provider emits a sorted prefix when
enumeration exceeds its cap. Exceeding a buffer or visit budget is a typed
family limitation, not permission to materialize more. The engine updates
stored frontier/visited/provider state atomically before issuing the next
handle. `FileTypeIndex.getFiles(...).toList()`, `ReferencesSearch.findAll`,
unbounded declaration walks, and inheritor `findAll()` are prohibited. Tests
tripwire those APIs, count provider visits, exercise the exact cap and cap plus
one, assert overflow returns no partial page, force a page boundary within one
file/frontier/provider stream, and prove page two neither revisits nor skips
evidence.

The hard SQLite impact offset ceiling is 10,000. Impact keeps its separate
exact node count and applies `LIMIT limit + 1 OFFSET offset` to the ordered row
query. The extra record proves continuation and is never emitted. IDEA
references are exact only after exhaustive traversal; INDEX references are
exact only after the exact target-anchor query is exhausted. Otherwise they
remain `KNOWN_MINIMUM`. SQLite impact tokens remain stateless and do not
promise a snapshot across index changes. #337 reference and every other
compiler continuation instead reject a handle when the backend-held semantic
generation changes; they never apply old provider state to new PSI.

## Degraded outcomes

Missing semantic capability is an expected typed result, not an empty
relationship list and not a generic transport failure. Each response root owns
its own degraded-reason enum; there is no shared `RelationDegradedCode` whose
values can be combined with the wrong family:

| Response family | Closed degraded reasons |
| --- | --- |
| `KastReferencesResponse` | `REFERENCES_UNAVAILABLE`, `INDEX_IDENTITY_UNAVAILABLE`, `BOUND_SOURCE_UNAVAILABLE`, `CANDIDATE_BUDGET_REACHED` |
| `KastCallersResponse` | `CALL_HIERARCHY_UNAVAILABLE`, `CANDIDATE_BUDGET_REACHED`, `TRAVERSAL_STATE_BUDGET_REACHED`, `TIMEOUT` |
| `KastImplementationsResponse` | `IMPLEMENTATIONS_UNAVAILABLE`, `CANDIDATE_BUDGET_REACHED`, `TRAVERSAL_STATE_BUDGET_REACHED`, `TIMEOUT` |
| `KastHierarchyResponse` | `TYPE_HIERARCHY_UNAVAILABLE`, `CANDIDATE_BUDGET_REACHED`, `TRAVERSAL_STATE_BUDGET_REACHED`, `TIMEOUT` |
| Rust `AgentImpactResult` | `SOURCE_INDEX_UNAVAILABLE`, `IMPACT_INDEX_IDENTITY_UNAVAILABLE`, `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` |

An unavailable or exact-anchor-unsafe SQLite reference index is not degradation
on a first page when IDEA reference search remains available. The returned
#337 cursor is then bound to IDEA. `REFERENCES_UNAVAILABLE` means the backend
cannot provide reference semantics at all. A continuation whose backend-held
source disappears returns the references-family `BOUND_SOURCE_UNAVAILABLE`
rather than switching sources. Overload-aggregated impact uses the impact-only
reasons described above.

A degraded result has outcome `DEGRADED`, carries its response family's closed
reason enum, names the missing capability or index evidence, omits records and
page claims, and preserves the verified subject plus exact selector.
Family-typed cursor-stale and cursor-invalid outcomes are separate closed
expected variants and also preserve the selector. Operational backend failures,
malformed payloads, and exact-root admission failures remain structured
JSON-RPC/command errors with closed error codes rather than
`Failure(code: String)` result variants or degradation.

## Source and issue boundaries

The Rust CLI owns public command parsing, anchored identity-selector
validation, query fingerprints, public page tokens, compact family
projections, source-index impact paging, and removal of the one-shot symbol
relationship path in `symbol_lookup.rs`. The Kotlin API and server own
host-agnostic relationship queries, typed expected outcomes, capability
mapping, handle transport, and full-fidelity responses. Runtime backends own
all semantic continuation state, generation/query/source validation, compiler
relationship collection, containing-symbol evidence, deterministic bounded
provider strategy, and atomic read-action traversal. `analysis-server` must not
create a second continuation store. `Location.usageSiteScope` remains #337's
optional structural scope;
`ReferenceOccurrence.containingSymbol` is the semantic identity proof. Neither
is derived from the other, and both are collected in the same bounded PSI pass
without Rust follow-up calls.

Issue #338 owns workspace-file inventory, file filters, index/filesystem
drift, and the initial public-capability route registry. Relationship
navigation must not use that inventory as semantic relationship evidence or
edit its issue-owned inventory files. A workspace-file `filePath` may compose
as a relationship `--declaration-file` without creating a dependency.

Issue #340 owns Gradle Kotlin DSL script classification, task/plugin/dependency
relationships, and Gradle-specific completeness. Kotlin `callers`,
`implementations`, and `hierarchy` must not claim Gradle task or plugin
relationships. Issue #342 may register these commands in the complete public
capability map after both lanes land; #339 proves callability directly from
the Clap tree and does not create a second prose capability catalog.

## Source of truth

| Contract | Owner |
| --- | --- |
| Exact anchored identity output | `cli-rs/src/agent/projection/symbol.rs`, `analysis-api` symbol identity contracts |
| Public commands, selectors, limits, directions, page tokens, and old-flag removal | `cli-rs/src/cli/agent.rs`, `cli-rs/src/agent/relations.rs`, `cli-rs/src/agent/symbol_lookup.rs` |
| Compact typed relation projections | `cli-rs/src/agent/projection/relations.rs` |
| Anchored references/call/type requests and typed relationship responses | `analysis-api`, `analysis-server` |
| Query/source/generation-bound reference and traversal state | `backend-idea` `ServerHeldContinuationStore` and family states |
| Compiler relationship evidence and deterministic bounded candidate traversal | `backend-idea` bounded reference/call/inheritor providers, `backend-shared` pure traversal models |
| Source-index impact identity/count/page reads | `cli-rs/src/metrics_database/`, production `declarations` schema |
| Rust relationship ownership and required gates | `cli-rs/src/agent/AGENTS.md` |
| Module ownership and atomicity/schema gates | `analysis-api/AGENTS.md`, `analysis-server/AGENTS.md`, `backend-idea/AGENTS.md`, `index-store/AGENTS.md`, `cli-rs/src/metrics_database/AGENTS.md` |
| Public examples and installed routing | `docs/reference/agent-commands.md`, `cli-rs/resources/kast-skill/` |
| Budget, composition, and paging gates | `cli-rs/tests/agent_relationship_navigation_smoke.rs` |

Generated catalogs and protocol files remain outputs. Edit their source
owners and regenerate them.

## Validation

```console
./gradlew :analysis-api:test :analysis-server:test :backend-shared:test :backend-idea:test :index-store:test
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke --test agent_command_surface_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

## Change rule

New relationship families, selector fields, count semantics, token or handle
versions, state lifetime/capacity, generation semantics, default budgets, or
capability fallbacks require a superseding ADR. Future work must preserve
anchored overload identity, lossless #337 cardinality and reference cursor
evidence, typed degraded/stale/invalid outcomes, deterministic bounded provider
work, and closed per-family records. A generic relation string, unchecked raw
position, arbitrary JSON filter, client-serialized traversal frontier, or
unbounded detailed view is not an acceptable public extension point.
