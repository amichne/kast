# ADR 0022: Identity-first relationship navigation

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 only for the public relationship-navigation
surface and supersedes ADR 0016 only for the one-shot relationship flags on
`kast agent symbol`. It extends ADR 0020's compact projection and bounded-work
rules. Issue #337 must land before this decision is implemented because its
reference paging, relationship budgets, impact counts, and projection types
are the starting contract. The names `ReferencePageCursor` and
`ResultCardinality` in this ADR refer to the current #337 branch and remain
provisional until that issue freezes; #339 rebases and adapts to the landed
types instead of duplicating them.

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
An indexed fallback candidate without a trustworthy canonical declaration file
or declaration offset returns typed `IDENTITY_ANCHOR_UNAVAILABLE`; it must not
emit a reusable-looking partial identity.

An absent anchor is a typed command-usage failure. A missing declaration is
`SUBJECT_NOT_FOUND`; an anchor that now resolves to another declaration is
`SUBJECT_IDENTITY_MISMATCH`. `SUBJECT_AMBIGUOUS` remains an exact-lookup
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
location list.

The source index currently keys impact edges by FQ name, not declaration
anchor or callable signature. Impact first verifies the anchored subject. If
more than one declaration shares that FQ name, it returns `DEGRADED` with
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` and the aggregate FQ name; it does
not present FQ-wide edges as impact for the selected overload. A unique
declaration may use the FQ-wide index result normally.

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
another record reports `KNOWN_MINIMUM` and a continuation token. No partial
count may pose as the total relation count, and #339 does not rename this
contract to `LOWER_BOUND`.

## Paging and deterministic work

Every relationship family uses the same public page evidence:

- `returnedCount` is the number of records on this page;
- `cardinality` is lossless #337 `ResultCardinality.EXACT` or
  `ResultCardinality.KNOWN_MINIMUM`;
- `truncated` agrees with the existence of more known work; and
- `nextPageToken` is present exactly when another page is known.

Public page tokens are opaque, versioned, and query-bound. The typed Rust token
includes the relation family and a fingerprint of the normalized workspace
root, complete anchored selector, declaration-inclusion choice,
direction/depth where applicable, and page limit. Passing a token to another
relation, subject, workspace, or budget returns
`RELATION_PAGE_TOKEN_MISMATCH` before backend work.

Reference tokens wrap #337's `ReferencePageCursor(source, evidenceOffset,
returnedBefore)` losslessly; the source is exactly `INDEX` or `IDEA`, and a
later page cannot silently switch evidence sources. Impact tokens carry a
validated SQLite offset. These small typed payloads use canonical ASCII fields
and existing SHA-256/hex support; they do not require a base64 dependency.

Call, implementation, and type-hierarchy continuation is stateful. Their token
carries an opaque server-issued handle, not serialized traversal internals. A
bounded server store owns a family-specific `RelationTraversalState` containing
the breadth-first frontier, visited identities, per-provider continuation,
consumed evidence, returned-before proof, normalized query fingerprint, and
semantic workspace generation. Handles have a bounded lifetime and store
capacity: 15 minutes, at most 1,024 live handles per exact workspace runtime,
and at most 16,384 frontier/visited/provider entries per state. Runtime restart,
eviction, expiry, or semantic-generation change
returns typed `RELATION_CURSOR_STALE`; malformed, unknown, wrong-family, or
query-mismatched handles return typed `RELATION_CURSOR_INVALID`. Neither state
may restart traversal from zero or silently select another subject.

For an unchanged admitted workspace, relation ordering is deterministic:

- references sort by file path, start offset, end offset, and containing
  symbol identity;
- call edges use breadth-first depth, parent identity, related identity, and
  call-site location;
- implementations and hierarchy nodes sort by fully-qualified name, kind,
  file path, and declaration offset; and
- impact nodes sort by depth, source path, target identity, and edge kind.

`offset + limit + 1` is an emitted-record window, not by itself a backend-work
bound. Compiler resolvers accept the current family state and a candidate-visit
budget before they materialize edges. Incoming calls iterate deterministic
file/offset evidence, outgoing calls iterate lexical PSI offsets, and subtype
relations iterate canonical class-index keys; each adapter stops its provider
at the budget and returns `visitedCandidateCount`, consumed evidence, its next
provider continuation, and exhaustiveness. The engine updates the stored
frontier and visited set atomically before issuing the next handle. Full
`ReferencesSearch` collection, unbounded declaration walks, and `findAll()`
inheritor materialization are prohibited. Tests count provider visits, force a
page boundary within one frontier node and one provider stream, and prove that
page two neither revisits nor skips evidence.

The hard cursor evidence-offset ceiling is 10,000. SQLite impact keeps its
separate exact count query and applies `LIMIT limit + 1 OFFSET offset` to the
ordered row query. The extra record proves continuation and is never emitted.
IDEA references are exact only after exhaustive traversal or when an
authoritative exact source-index count covers the same query; otherwise they
remain `KNOWN_MINIMUM`. #337 reference and SQLite impact tokens remain
stateless and do not promise a snapshot across source edits. Stateful compiler
traversal instead rejects a continuation when its bound semantic generation
changes; it never applies an old frontier to new PSI.

## Degraded outcomes

Missing semantic capability is an expected typed result, not an empty
relationship list and not a generic transport failure. The mappings are:

| Command | Required evidence | Degraded code |
| --- | --- | --- |
| `references` | `FIND_REFERENCES` | `REFERENCES_UNAVAILABLE` |
| `callers`, `callees` | `CALL_HIERARCHY` | `CALL_HIERARCHY_UNAVAILABLE` |
| `implementations` | `IMPLEMENTATIONS` | `IMPLEMENTATIONS_UNAVAILABLE` |
| `hierarchy` | `TYPE_HIERARCHY` | `TYPE_HIERARCHY_UNAVAILABLE` |
| `impact` | compatible exact-root source index | `SOURCE_INDEX_UNAVAILABLE` |

An unavailable SQLite reference index is not degradation when IDEA reference
search remains available: #337's selected reference source and completion
evidence are preserved. `REFERENCES_UNAVAILABLE` means the backend cannot
provide reference semantics at all. A continuation whose bound reference
source disappears returns `REFERENCE_CURSOR_SOURCE_UNAVAILABLE` rather than
switching sources. Overload-aggregated impact uses the separate
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` code described above.

A degraded result has outcome `DEGRADED`, carries a closed degraded-code enum,
names the missing capability or index evidence, omits records and page claims,
and preserves the verified subject plus exact selector. Cursor-stale and
cursor-invalid outcomes are separate closed expected variants and also preserve
the selector. Operational backend failures, malformed payloads, and exact-root
admission failures remain structured JSON-RPC/command errors with closed error
codes rather than `Failure(code: String)` result variants or degradation.

## Source and issue boundaries

The Rust CLI owns public command parsing, anchored identity-selector
validation, query fingerprints, public page tokens, compact family
projections, source-index impact paging, and removal of the one-shot symbol
relationship path in `symbol_lookup.rs`. The Kotlin API and server own
host-agnostic relationship queries, typed expected outcomes, capability
mapping, bounded traversal-state storage, generation/query validation, and
full-fidelity responses. Runtime backends own compiler relationship collection,
semantic-generation evidence, containing-symbol evidence, deterministic
ordering, provider continuation, and bounded traversal. `Location.usageSiteScope`
remains #337's optional structural scope;
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
| Generation/query-bound traversal handle store | `analysis-server` relation traversal state owners |
| Compiler relationship evidence and deterministic bounded candidate traversal | `backend-idea` resolvers, `backend-shared` hierarchy engines |
| Source-index impact count and page reads | `cli-rs/src/metrics_database/` |
| Rust relationship ownership and required gates | `cli-rs/src/agent/AGENTS.md` |
| Public examples and installed routing | `docs/reference/agent-commands.md`, `cli-rs/resources/kast-skill/` |
| Budget, composition, and paging gates | `cli-rs/tests/agent_relationship_navigation_smoke.rs` |

Generated catalogs and protocol files remain outputs. Edit their source
owners and regenerate them.

## Validation

```console
./gradlew :analysis-api:test :analysis-server:test :backend-shared:test :backend-idea:test
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
