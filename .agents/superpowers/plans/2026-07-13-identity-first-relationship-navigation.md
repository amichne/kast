# Identity-first Relationship Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship standalone, compact, identity-first Kotlin relationship
commands with deterministic bounded paging and typed degraded outcomes.

**Architecture:** Exact symbol lookup produces one anchored canonical identity
that five typed relationship commands plus impact consume without overload
re-resolution. Kotlin skill endpoints verify its declaration file/start offset,
runtime resolvers collect deterministic evidence under pre-materialization
candidate budgets, and `backend-idea` preserves all semantic continuation state
behind bounded query/source/generation-bound handles inside one read action.
`analysis-server` transports those handles without owning state. The Rust CLI
wraps #337's opaque reference handle, traversal handles, or impact offsets in
query-bound page tokens before projecting closed records. Source impact keeps
its Rust/SQLite path and conservatively degrades callables because the
production declaration key cannot prove same-file overload isolation.

**Tech Stack:** Kotlin/JVM, JUnit Jupiter, IDEA PSI/search APIs, Rust 2024,
Clap, serde, SHA-256, SQLite/rusqlite, test-only `tiktoken-rs`, scripted
Unix-socket integration tests, generated JSON Schema/OpenAPI contracts,
Markdown/Zensical.

## Global Constraints

- Rebase the implementation branch after issue #337 lands; do not recreate or
  bypass its compact projections, positive reference limit, separate impact
  count query, bounded `limit + 1` fetch, `ResultCardinality.EXACT` /
  `KNOWN_MINIMUM`, or opaque server-held query/source/generation-bound reference
  cursor. Adapt to the landed names without decoding or serializing its private
  provider position and returned-before proof.
- Use one non-private top-level Kotlin production type per matching file.
- Public commands accept anchored canonical identity only: FQ name,
  declaration file, and declaration start offset, with optional kind and
  containing type assertions. Under explicit `--workspace-root`, accept #341
  workspace-relative declaration paths and normalize/store the canonical path.
  Exact symbol output must expose those fields in one reusable `identity`
  object. This plan explicitly supersedes ADR 0016: `RESOLVED` and indexed
  fallback require exactly one complete canonical file/offset anchor; otherwise
  exact lookup returns `IDENTITY_ANCHOR_UNAVAILABLE`. Never invoke lexical
  discovery or accept arbitrary JSON.
- Default relationship limit is 4; valid limits are 1 through 200. Call and
  type hierarchy depth defaults to 1; valid depth is 1 through 8.
- After exact anchor verification, reject unsupported kinds with each response
  root's closed `UNSUPPORTED_SUBJECT_KIND` variant before provider/index work.
  References admit class/interface/object/function/property/parameter;
  callers/callees admit function; implementations admit class/interface;
  hierarchy admits class/interface/object; impact admits
  class/interface/object/function/property. `UNKNOWN` is never admitted. Test
  the full command-kind matrix with zero provider/index calls for every reject.
- Reference tokens preserve only #337's opaque `ReferencePageToken`. Its `INDEX|IDEA`
  source, provider position, returned-before, query, subject, and generation
  stay in backend-owned state. Impact tokens preserve a typed SQLite offset
  capped at 10,000. Call/type tokens carry a parallel opaque backend handle
  whose state preserves the BFS frontier, visited identities, provider
  continuation, consumed evidence, and returned-before proof. Detailed output
  never removes result, candidate-visit, or state bounds.
- Compact relationship output must remain at or below 120 lines and 1,500
  `cl100k_base` tokens for high-cardinality fixtures.
- Every response family owns its closed degraded-reason enum; no shared
  `RelationDegradedCode` exists. Reference-index absence or unsafe target-anchor
  evidence with a usable first-page IDEA fallback is not degraded. An
  INDEX-bound continuation never changes source. Callable impact uses
  `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`; malformed payloads and operational
  failures remain errors.
- Resolver candidate work is bounded before list materialization. Every
  compiler page reports visited-candidate count, consumed evidence, next
  provider continuation, and exhaustiveness; tests instrument provider visits
  and tripwire unbounded APIs. The one backend-owned semantic store has a
  15-minute TTL, at most 1,024 handles per exact workspace runtime, and at most
  16,384 candidate/frontier/visited/provider entries per state. Generation
  check, provider work, and next-state commit happen atomically in one backend
  read action. An absent canonical handle is always family-typed invalid
  `UNKNOWN_HANDLE`, including restart-to-fresh-backend, random UUID, replay, and
  eviction. Stale requires retained state that proves generation change, or
  retained expiry observed before removal.
- Migrate `KastReferencesRequest`, `KastReferencesQuery`, and
  `KastReferencesResponse` to the anchored selector; remove named resolution
  from the endpoint, extract `KastScaffoldReferences.kt`, and migrate
  scaffold/contract fixtures to `ReferenceOccurrence`. INDEX reads require the
  selected canonical target path and one non-null target offset.
- Degraded outcomes preserve selector and verified subject. Cursor stale and
  invalid are separate typed outcomes. Identity mismatch carries non-null
  actual identity; absence is subject-not-found. A continuation requires
  `KNOWN_MINIMUM >= returnedBefore + returnedCount + 1`. Operational failures
  use structured closed error codes, never `Failure(code: String)`.
- Do not edit issue #338's workspace-inventory implementation or use it as
  semantic relation evidence. Do not model issue #340's Gradle task, plugin,
  dependency, or build-logic relations as Kotlin relationships.
- Generated catalog, schema, protocol, and docs artifacts must come from their
  checked-in source owners.
- Update the nearest scoped `AGENTS.md` whenever this work changes public
  command ownership or validation gates. Runtime token formats use existing
  SHA-256/hex plus canonical ASCII and opaque handles. The executable exact
  output-budget gate intentionally adds `tiktoken-rs = "0.12"` as a dev
  dependency and updates `Cargo.toml` plus `Cargo.lock`; no runtime dependency
  is added.

---

## File structure

New and materially changed files have one responsibility:

- `cli-rs/src/agent/relations.rs` owns exact selector orchestration, relation
  request construction, public token validation, and degraded mapping.
- `cli-rs/src/agent/symbol_lookup.rs` owns removal of the old one-shot
  references/callers execution path when its flags leave `AgentSymbolArgs`.
- `cli-rs/src/agent/projection/relations.rs` owns closed compact, field, count,
  verbose, and explain relation projections.
- `cli-rs/src/agent/projection/symbol.rs` owns the one reusable anchored exact
  identity output.
- `cli-rs/src/agent/AGENTS.md` records the new public command, token, projection,
  and validation ownership.
- `cli-rs/tests/agent_relationship_navigation_smoke.rs` owns CLI composition,
  paging, degradation, and output-budget proof.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferenceOccurrence.kt`
  owns one reference occurrence.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolEvidence.kt`
  owns the closed containing declaration evidence variants.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolUnavailableReason.kt`
  owns the closed reasons that semantic containment could not be reported.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SymbolIdentity.kt`
  owns lightweight compiler identity with canonical declaration file and
  non-negative declaration start offset.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationPageInfo.kt`
  owns shared cardinality and page evidence. The landed #337 reference cursor
  remains an opaque backend-issued handle.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationTraversalHandle.kt`,
  `RelationCursorStaleReason.kt`, and `RelationCursorInvalidReason.kt` own the
  opaque handle and closed continuation outcomes.
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ServerHeldContinuationStore.kt`
  is the one semantic state owner; sealed reference/call/type states own TTL,
  capacity, query/source/subject/generation binding, private returned-before,
  frontier, visited identities, provider continuation, and accumulated proof.
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaBoundedReferenceProvider.kt`
  owns bounded `FileTypeIndex.processFiles` plus `PsiReferenceScanner` paging.
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaOutgoingLexicalDfsProvider.kt`
  owns resumable lexical DFS for outgoing calls; its bounded child-index stack
  and next-reference position are pure data under `backend-shared`.
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaBoundedInheritorProvider.kt`
  owns bounded `ClassInheritorsSearch.forEach` direct-inheritor paging.
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ObservedAnalysisBackend.kt`
  owns exact delegation and observation of the handle-bearing methods.
- `analysis-server` remains the transport/dispatch owner and contains no
  relationship continuation store.
- Family-specific pure state models under `backend-shared/.../hierarchy/`
  contain no PSI and never expose internals in a public token.
- `backend-shared` resolver interfaces own typed candidate-visit budgets and
  result evidence; `IdeaCallEdgeResolver` and `IdeaTypeEdgeResolver` own
  deterministic provider iteration before materialization.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsRequest.kt`
  and `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsResponse.kt`
  own identity-first implementation lookup.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyRequest.kt`
  and `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyResponse.kt`
  own identity-first type hierarchy lookup.
- Existing caller contract owners are extracted from `SkillContracts.kt` when
  materially edited. `KastReferencesRequest.kt`, `KastReferencesQuery.kt`, and
  `KastReferencesResponse.kt` migrate explicitly to the same anchored selector
  and closed outcomes; `KastScaffoldReferences.kt` is extracted explicitly;
  scaffold and backend contract fixtures migrate to `ReferenceOccurrence`.
  Direct sealed variants stay beside their response root.

## Test inventory

The implementation is incomplete until every row is executable:

| Gate | Cases |
| --- | --- |
| Public parsing | Commands visible; declaration file/offset required together; #341-relative file accepted; retired symbol flags and old execution path rejected; typed direction exhaustive; depth/limit/token ranges fail in Clap. |
| Identity | ADR 0016 exact success/fallback is superseded; one anchored identity object passes unchanged; same-file overloads resolve by file/start offset; anchor-unavailable/not-found/non-null mismatch stop before relation work; no discovery method called. |
| Subject kinds | The complete command-by-`SymbolKind` matrix returns closed `UNSUPPORTED_SUBJECT_KIND` for rejected pairs after identity verification and before provider/index work; every reject has a zero-work assertion. |
| References | Anchored `KastReferences*` contracts never call named resolution; `KastScaffoldReferences.kt`/fixtures consume occurrences; #337 opaque cursor round-trips without source/counter serialization; exact target path/offset isolates forced INDEX overloads; unsafe first INDEX falls back to IDEA while continuation never switches; plus-one cardinality, containment, stable pages, and 500-record budget are proved. |
| Atomic state | Backend store is sole owner; generation check/provider work/state commit share one read action; queued-write races and `ObservedAnalysisBackend` delegation are covered; server owns no state; A-issued/restart-to-B, random UUID, replay, and eviction are identically absent/invalid while retained generation mismatch is stale. |
| Calls | Incoming/outgoing fixed by command; BFS ordering; bounded `FileTypeIndex.processFiles`/`PsiReferenceScanner` incoming provider and resumable lexical-DFS outgoing provider; nested blocks/local initializers are visited, nested callable/type/lambda bodies are excluded, and page resume preserves the pure-data stack without revisit; depth, result, state, visit, cap-plus-one, and forbidden-materializer gates are tested. |
| Implementations | Interface implementation and class subclass records; exact versus known-minimum cardinality; stateful deterministic bounded provider pages; stale/invalid handles; capability absent degrades. |
| Hierarchy | Supertypes/subtypes/both; depth; cycle; stateful deterministic bounded provider pages; semantic-generation changes invalidate continuation; capability absent degrades. |
| Impact | Compiler position verifies the anchor; production row path/offset/kind gates non-callable impact; a production-store same-file overload regression proves FQ row counts cannot authorize callable impact; ordered `limit + 1 offset`, 503 records, and incompatible-index degradation are covered. |
| Projection | Wrong item family or family reason, nullable mismatch actual, invalid/plus-one counts, false exactness, omitted subject/selector, unsupported-kind mismatch, primitive failure code, token/truncation disagreement, and malformed subject fail closed. |
| Contracts | Catalog, schemas, samples, API docs, OpenAPI, packaged content, and public command docs regenerated and checked. |
| Output budget | `tiktoken-rs::cl100k_base()` encodes rendered compact JSON under the locked dev-dependency graph; every high-cardinality fixture stays within 120 lines and 1,500 tokens. |
| End to end | Resolve identity, prove references/callers, continue a page, estimate impact; no text search, raw dispatch, or unbounded request. |

### Task 1: Public command and bounded argument contract

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/symbol_lookup.rs`
- Create: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`
- Modify: `cli-rs/tests/cli_core_smoke.rs`

**Interfaces:**

- Consumes: ADR 0016 exact selector fields and ADR 0020 result-view arguments.
- Produces: `AgentReferencesArgs`, `AgentCallersArgs`, `AgentCalleesArgs`,
  `AgentImplementationsArgs`, `AgentHierarchyArgs`, `AgentRelationLimit`,
  `AgentRelationDepth`, `AgentHierarchyDirection`, and
  anchored `AgentExactSymbolSelectorArgs` for later execution tasks; removes
  the old `compiler_symbol_relations` execution path.

- [ ] **Step 1: Write RED parsing and help tests**

Add tests that parse every valid command and reject the replaced symbol flags:

```rust
#[test]
fn relationship_commands_are_public_and_symbol_relation_flags_are_retired() {
    fn assert_cli_reaches_execution(args: &[&str]) {
        let temp = tempfile::tempdir().expect("tempdir");
        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(args)
            .output()
            .expect("kast command");
        assert_ne!(output.status.code(), Some(2));
    }

    fn assert_cli_rejects(args: &[&str]) {
        let temp = tempfile::tempdir().expect("tempdir");
        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(args)
            .output()
            .expect("kast command");
        assert_eq!(output.status.code(), Some(2));
    }

    for command in [
        [
            "agent",
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "40",
        ]
        .as_slice(),
        [
            "agent",
            "callers",
            "--symbol",
            "sample.Service.run",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "80",
        ]
        .as_slice(),
        [
            "agent",
            "callees",
            "--symbol",
            "sample.Service.run",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "80",
        ]
        .as_slice(),
        [
            "agent",
            "implementations",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "40",
        ]
        .as_slice(),
        [
            "agent",
            "hierarchy",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "40",
            "--direction",
            "both",
        ]
        .as_slice(),
    ] {
        assert_cli_reaches_execution(command);
    }
    assert_cli_rejects(&["agent", "symbol", "--query", "Service", "--references"]);
    assert_cli_rejects(&[
        "agent",
        "symbol",
        "--query",
        "Service.run",
        "--callers",
        "incoming",
    ]);
}
```

Capture stderr for both retired forms and assert a stable tombstone names
`kast agent references` or `kast agent callers` respectively. The tombstone
must be produced before runtime discovery and must not preserve a hidden
one-shot execution branch.

Add a table test for missing file, missing offset, negative/malformed offset,
limits `0`/`201`, depths `0`/`9`, unknown hierarchy direction, empty symbol,
and malformed page token. Prove `src/Service.kt` under an explicit workspace
root is accepted and normalized canonically. Assert exit code 2 and that no
runtime descriptor or source-index fixture is opened for invalid input.

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke relationship_commands_are_public_and_symbol_relation_flags_are_retired
```

Expected: FAIL because the five command variants do not exist and the old
symbol flags still parse.

- [ ] **Step 3: Add concrete Clap variants and validated newtypes**

Add top-level `AgentCommand` variants and flatten shared selector/runtime/view
arguments. The production types must encode these exact ranges:

```rust
const DEFAULT_RELATION_LIMIT: u8 = 4;
const MAX_RELATION_LIMIT: u16 = 200;
const DEFAULT_RELATION_DEPTH: u8 = 1;
const MAX_RELATION_DEPTH: u8 = 8;
const MAX_RELATION_PAGE_OFFSET: u16 = 10_000;

#[derive(Debug, Args, Clone)]
pub struct AgentExactSymbolSelectorArgs {
    #[arg(long, value_parser = parse_canonical_symbol_name)]
    pub symbol: CanonicalSymbolName,
    #[arg(long = "declaration-file")]
    pub declaration_file: WorkspaceDeclarationFile,
    #[arg(long = "declaration-start-offset")]
    pub declaration_start_offset: DeclarationStartOffset,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long, value_parser = parse_canonical_symbol_name)]
    pub containing_type: Option<CanonicalSymbolName>,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentHierarchyDirection {
    Supertypes,
    Subtypes,
    Both,
}
```

Use Clap range parsers for `--limit 1..=200`, `--depth 1..=8`, and a
non-negative declaration offset. Remove `references`, `callers`, and
`caller_depth` from `AgentSymbolArgs`; leave its `--limit` responsible only for
discovery candidate cardinality. In `symbol_lookup.rs`, delete
`compiler_symbol_relations`, its exact-mode branches, and discovery-mode flag
checks that can no longer parse. Add the pre-parse usage tombstone for only the
retired flag spellings; it reports the replacement command and exits 2 without
constructing `AgentSymbolArgs`. Add a source-level smoke assertion that the
removed function and field reads are absent so the old one-shot path cannot
survive behind hidden routing.

- [ ] **Step 4: Run parsing tests GREEN**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke
```

Expected: all parsing/help tests PASS; execution-oriented tests may remain
ignored only if their test function is not added until the owning task.

- [ ] **Step 5: Commit the command contract**

```console
git add cli-rs/src/cli/agent.rs cli-rs/src/agent.rs cli-rs/src/agent/symbol_lookup.rs cli-rs/tests/agent_relationship_navigation_smoke.rs cli-rs/tests/agent_command_surface_smoke.rs cli-rs/tests/cli_core_smoke.rs
git commit -m "feat: define typed relationship commands"
```

### Task 2: Shared reference occurrence and page evidence

**Files:**

- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SymbolIdentity.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolEvidence.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolUnavailableReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferenceOccurrence.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationTraversalPageInfo.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationTraversalHandle.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationCursorStaleReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationCursorInvalidReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastExactSymbolSelector.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedExactSymbolSelector.kt`
- Modify after #337 lands: its opaque `ReferencePageToken`, page/cardinality,
  and reference query owner files under `analysis-api/src/main/kotlin/`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/ReferencesQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferencesResult.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedReferencesQuery.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/RelationshipModelTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- Modify: `analysis-api/AGENTS.md`

**Interfaces:**

- Consumes: #337's `PositiveInt`, `ReferencesQuery.maxResults`, opaque
  backend-issued `ReferencePageToken`, `ResultCardinality.EXACT|KNOWN_MINIMUM`,
  and `PageInfo` behavior. Source, provider position, returned-before, query,
  subject, and generation remain backend-held.
- Produces: `SymbolIdentity`, closed `ContainingSymbolEvidence`,
  `ReferenceOccurrence`, `RelationTraversalPageInfo`, anchored request selector,
  opaque traversal handle, and closed stale/invalid reasons. It does not replace
  or decode #337's reference token and does not serialize call/type frontier
  state into the handle.

- [ ] **Step 1: Write RED model tests for proof states and page invariants**

Add a JSON round-trip test for all containing-symbol variants and invalid page
claims. The expected wire values are:

```kotlin
val known = ContainingSymbolEvidence.Known(
    SymbolIdentity(
        fqName = "sample.Controller.handle",
        kind = SymbolKind.FUNCTION,
        declarationFile = NormalizedPath.parse("/repo/Controller.kt"),
        declarationStartOffset = NonNegativeInt(10),
        containingType = "sample.Controller",
    ),
)
val topLevel = ContainingSymbolEvidence.TopLevel
val unavailable = ContainingSymbolEvidence.Unavailable(
    ContainingSymbolUnavailableReason.NO_SEMANTIC_OWNER,
)
```

Round-trip one canonical UUID `ReferencePageToken` without decoding it and an
opaque backend-issued `RelationTraversalHandle` with every stale/invalid reason.
Reject blank, malformed-version, non-ASCII, and overlong traversal handles at
the parser; backend-store tests reject a syntactically valid client-invented
handle as typed invalid. Test that page factories reject returned count greater
than exact cardinality, a continuation whose `KNOWN_MINIMUM` is below
`returnedBefore + returnedCount + 1`, a next handle without truncation,
truncation without a next handle, and visited count above the declared
candidate budget. Reference tests prove the same plus-one invariant using the
private #337 continuation state.
Define stale reasons only for positively recognized retained state
(`GENERATION_CHANGED`, plus `EXPIRED` when typed lookup observes the retained
expired entry). Define invalid `UNKNOWN_HANDLE`, `FAMILY_MISMATCH`, and
`QUERY_MISMATCH`; absence after restart, eviction, replay, or a random canonical
UUID all uses `UNKNOWN_HANDLE`.

- [ ] **Step 2: Run the API tests and confirm RED**

Run:

```console
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.RelationshipModelTest --no-daemon
```

Expected: FAIL at Kotlin compilation because the proof and page types do not
exist.

- [ ] **Step 3: Implement the host-agnostic types and migrate references**

Use these ownership signatures:

```kotlin
data class SymbolIdentity(
    val fqName: String,
    val kind: SymbolKind,
    val declarationFile: NormalizedPath,
    val declarationStartOffset: NonNegativeInt,
    val containingType: String? = null,
)

sealed interface ContainingSymbolEvidence {
    data class Known(val symbol: SymbolIdentity) : ContainingSymbolEvidence
    data object TopLevel : ContainingSymbolEvidence
    data class Unavailable(
        val reason: ContainingSymbolUnavailableReason,
    ) : ContainingSymbolEvidence
}

data class ReferenceOccurrence(
    val location: Location,
    val containingSymbol: ContainingSymbolEvidence,
)

data class RelationTraversalPageInfo private constructor(
    val cardinality: ResultCardinality,
    val returnedCount: Int,
    val visitedCandidateCount: Int,
    val truncated: Boolean,
    val nextHandle: RelationTraversalHandle?,
) {
    companion object {
        fun create(
            cardinality: ResultCardinality,
            returnedCount: Int,
            returnedBefore: Int,
            visitedCandidateCount: Int,
            candidateVisitLimit: Int,
            nextHandle: RelationTraversalHandle?,
        ): RelationTraversalPageInfo
    }
}

@JvmInline
value class RelationTraversalHandle private constructor(val value: String)

```

Change `ReferencesResult.references` to `List<ReferenceOccurrence>`. Replace
#337's bare locations but preserve opaque `ReferencePageToken` and
`ResultCardinality` losslessly. Keep `Location.usageSiteScope` and
`ReferencesQuery.includeUsageSiteScope` as the optional structural-scope
contract; `ReferenceOccurrence.containingSymbol` is separate semantic identity
evidence. Record the opaque #337 token dependency and shared relationship
contract in `analysis-api/AGENTS.md` without exposing backend-held token state.

`KastExactSymbolSelector` is the serializable request DTO. Its parsed form owns
`NonBlankString`, `NormalizedPath`, `NonNegativeInt`, optional typed kind, and
optional non-blank containing type. `RelationTraversalHandle` has no public
client factory: the runtime backend issues canonical `rth1` handles and the
request boundary only parses them. Reference source/counters/generation and
call/type frontier state do not appear in this host-agnostic wire model.

- [ ] **Step 4: Run API tests GREEN**

Run:

```console
./gradlew :analysis-api:test --no-daemon
```

Expected: BUILD SUCCESSFUL; every public production type is in its matching
file.

- [ ] **Step 5: Commit the shared proof model**

```console
git add analysis-api
git commit -m "feat: model typed relationship evidence"
```

### Task 3: Deterministic reference pages with containing symbols

**Files:**

- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastReferencesRequest.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastReferencesQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastReferencesResponse.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastReferencesDegradedReason.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastScaffoldReferences.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/contract/skill/SymbolQuerySchemaContractTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/AnalysisBackendContractFixture.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Modify after #337 lands: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ReferenceIndexLookup.kt`
- Extract/modify after #337 lands: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ServerHeldContinuationStore.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaBoundedReferenceProvider.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ObservedAnalysisBackend.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/ObservedAnalysisBackendTest.kt`
- Modify: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt`
- Modify: `index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStoreTest.kt`
- Modify: `index-store/AGENTS.md`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`

**Interfaces:**

- Consumes: `ReferenceOccurrence`, `ContainingSymbolEvidence`, #337's positive
  reference limit, opaque backend-held `ReferencePageToken`,
  `ResultCardinality`, optional `includeUsageSiteScope`, and the anchored exact
  selector from Task 2.
- Produces: deterministic bounded reference pages, truthful exact or
  known-minimum cardinality, source-pinned continuation, and semantic
  containing-symbol proof for an overload-safe `symbol/references` endpoint.

- [ ] **Step 1: Add RED backend and server scenarios**

Build one Kotlin fixture containing a member call, a top-level call, and a
reference whose nearest PSI owner cannot be converted to a supported symbol.
Assert the three containing-symbol outcomes. Add 205 ordered usages and assert:

```kotlin
assertEquals(first.references.map { it.location }.toSet().size, first.references.size)
assertTrue(first.references.none { it in second.references })
assertEquals((1..4).toList(), first.references.map { it.location.startLine })
assertEquals((5..8).toList(), second.references.map { it.location.startLine })
assertEquals(ResultCardinality.KnownMinimum(5), first.cardinality)
val firstToken = ReferencePageToken.parse(requireNotNull(first.page?.nextPageToken))
assertEquals(firstToken, ReferencePageToken.parse(firstToken.value))
assertTrue(firstToken.value.matches(UUID_PATTERN))
```

Add an exhaustive four-reference fixture that reports
`ResultCardinality.Exact(4)`. Seed production `symbol_references` rows for two
same-FQ functions in one file at different non-null target offsets. Force INDEX
for the second anchor and prove only its rows are returned. Add null and mixed
target-anchor rows: on a first request they make INDEX exact identity
unavailable and IDEA is selected; after an INDEX token exists, index loss or
unsafe identity returns references-family `BOUND_SOURCE_UNAVAILABLE` or
`INDEX_IDENTITY_UNAVAILABLE` and an IDEA tripwire remains untouched. Inspect
the backend test store to prove the opaque token binds source, provider
position, returned-before, query, subject, and generation; none appear in JSON.
Have backend A issue a reference token, then present it to fresh backend B and
compare the result with a never-issued random canonical UUID. Both are
references cursor-invalid `UNKNOWN_HANDLE` with zero INDEX/IDEA provider work.
A retained token whose stored generation differs is cursor-stale
`GENERATION_CHANGED`; replay after consumption is absent/invalid.

Pass the second exact anchor and prove the raw backend receives only that
file/offset while a `resolveNamedSymbol` tripwire remains untouched. At the
dispatcher boundary, assert `maxResults=4` and the opaque token become typed
query values. Malformed tokens return the relationship usage error without
calling the fake backend. Assert every expected response preserves the selector
or verified subject and mismatch actual is non-null. Extract
`KastScaffoldReferences.kt`; add a `symbol/scaffold` dispatcher scenario that
proves `ReferenceOccurrence.containingSymbol` survives the orchestrator adapter
rather than becoming `List<Location>`.
Exercise every `SymbolKind`: references admit all known kinds except `UNKNOWN`;
the rejected case returns `UNSUPPORTED_SUBJECT_KIND` after anchor verification
and before INDEX or IDEA work.

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```console
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.KastPluginBackendContractTest --no-daemon
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherTest --no-daemon
```

Expected: FAIL because references still expose bare locations and no
containing-symbol evidence.

- [ ] **Step 3: Collect proof while PSI is owned and preserve bounded work**

Use #337's selected source and opaque token as authority, but refactor its
backend-held state to pure anchors/provider positions rather than smart
pointers or `IdeaReferenceTraversal`. Replace destructive untyped `take` with a
typed atomic claim: retained, retained-expired, or absent. Absent always maps
to invalid `UNKNOWN_HANDLE`; stale requires retained generation mismatch or
retained expiry. In one `timedReadAction`, validate the
stored generation and query, ask the bound source for at most the evidence
needed to return `limit + 1` usable occurrences, and commit the next state
before leaving the read action. Map those bounded usages to
`ReferenceOccurrence` in that same action.
Convert the nearest supported containing declaration to `SymbolIdentity`; emit
`TopLevel` only after proving no containing declaration, and `Unavailable` when
a declaration exists but semantic conversion fails. Preserve separately
requested `Location.usageSiteScope` on the same bounded PSI pass. Sort by
normalized file path, start offset, end offset, and known containing FQ name.
Return `EXACT` only after source exhaustion or from an authoritative exact
target-anchor INDEX count for the same query; otherwise preserve
`KNOWN_MINIMUM`. A continuation requires at least private
`returnedBefore + returnedCount + 1`. Drop only the extra proof occurrence.

Change `ReferenceIndexLookup` and `SqliteSourceIndexStore` from FQ-only reads to
a typed exact target `(fqName, canonicalTargetPath, targetOffset)`. Every
selected `SymbolReferenceRow` repeats that target. Use
`IdeaBoundedReferenceProvider`: stream `FileTypeIndex.processFiles` into the
bounded state buffer through cap plus one, then stop. Cap-plus-one returns the
references-family budget reason with no records, page claim, or retained
partial state. At or below the cap, sort the complete admitted path snapshot
and scan it with `PsiReferenceScanner` in lexical offset order. Do not call
`FileTypeIndex.getFiles(...).toList()` or `ReferencesSearch.findAll`; cap and
cap-plus-one tests tripwire both and prove that no sorted prefix escapes on
overflow.

Change `KastReferencesRequest` and `KastReferencesQuery` to carry
`KastExactSymbolSelector`. Replace success/failure with references-owned closed
available, subject-not-found, identity-mismatch, unsupported-subject-kind,
degraded, cursor-stale, and cursor-invalid variants. Apply the reference
subject-kind boundary before source selection.
`KastReferencesDegradedReason` alone owns
`REFERENCES_UNAVAILABLE`, `INDEX_IDENTITY_UNAVAILABLE`,
`BOUND_SOURCE_UNAVAILABLE`, and `CANDIDATE_BUDGET_REACHED`. Resolve the
canonical declaration file/start offset directly, verify every identity field,
and delete the endpoint's `resolveNamedSymbol` call. Operational failures
remain structured RPC errors. Migrate `KastScaffoldReferences` and its fixtures
to its own file and to `ReferenceOccurrence` so no adapter discards
containing-symbol proof. `ObservedAnalysisBackend` delegates the opaque token
unchanged and records exactly one `FIND_REFERENCES` operation; a queued-write
race proves no write can occur between generation validation, provider work,
and next-state commit.

- [ ] **Step 4: Run reference tests GREEN and inspect diagnostics**

Run:

```console
./gradlew :analysis-server:test :backend-idea:test :index-store:test --no-daemon
```

Expected: BUILD SUCCESSFUL. If the IntelliJ-prepared primary workspace can
analyze the changed files with the running binary, also run typed Kast
diagnostics there; an unprepared implementation worktree must report the
plugin-metadata limitation and must not run `kast setup`.

- [ ] **Step 5: Commit reference evidence**

```console
git add analysis-api analysis-server backend-idea index-store
git commit -m "feat: report bounded reference ownership"
```

### Task 4: Identity-first call, implementation, and hierarchy endpoints

**Files:**

- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastCallersRequest.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastCallersQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastCallersResponse.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsRequest.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsResponse.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyRequest.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyResponse.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/CallRelation.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ImplementationRelation.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/TypeHierarchyRelation.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastCallDegradedReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsDegradedReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyDegradedReason.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/CallHierarchyQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/ImplementationsQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/TypeHierarchyQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/AnalysisBackend.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/CallEdgeResolver.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/TypeEdgeResolver.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/RelationCandidateVisitBudget.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/RelationCandidateBatch.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/CallTraversalState.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/OutgoingLexicalDfsState.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/TypeTraversalState.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/RelationTraversalStateBudget.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/CallHierarchyEngine.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/TypeHierarchyEngine.kt`
- Create: `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/hierarchy/RelationshipPagingTest.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaCallEdgeResolver.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaOutgoingLexicalDfsProvider.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaTypeEdgeResolver.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ServerHeldContinuationStore.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaBoundedInheritorProvider.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ObservedAnalysisBackend.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastDiagnosticsService.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaRelationCandidateBudgetTest.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaOutgoingLexicalDfsProviderTest.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/ObservedAnalysisBackendTest.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`
- Modify: `analysis-server/AGENTS.md`
- Create: `backend-idea/AGENTS.md`

**Interfaces:**

- Consumes: anchored selector outcomes, opaque `RelationTraversalHandle`,
  positive limits, `SymbolIdentity`, backend-owned semantic generation,
  capability enums, and full-fidelity raw hierarchy results.
- Produces: internal `symbol/implementations` and `symbol/hierarchy` methods;
  statefully paged flat call/implementation/hierarchy records; typed available,
  subject-not-found, identity-mismatch, unsupported-subject-kind, degraded,
  cursor-stale, and cursor-invalid variants; operational faults remain RPC
  errors. Exact-lookup ambiguity does not leak into anchored relationship
  responses.

- [ ] **Step 1: Add RED model and traversal tests**

Create balanced and cyclic call/type graphs whose insertion order differs from
canonical order. Assert breadth-first call pages and identity-sorted
implementation/type pages. The page window assertion is:

```kotlin
val visitBudget = RelationCandidateVisitBudget(65)
val first = engine.start(
    limit = PositiveInt(4),
    candidateVisitBudget = visitBudget,
    stateBudget = RelationTraversalStateBudget.default(),
)
val second = engine.resume(
    state = requireNotNull(first.nextState),
    limit = PositiveInt(4),
    candidateVisitBudget = visitBudget,
)
assertEquals(4, first.records.size)
assertEquals(4, second.records.size)
assertTrue(first.records.toSet().intersect(second.records.toSet()).isEmpty())
assertEquals(ResultCardinality.KnownMinimum(5), first.page.cardinality)
assertEquals(ResultCardinality.Exact(8), second.page.cardinality)
assertTrue(first.page.visitedCandidateCount <= visitBudget.value)
assertTrue(first.visitedIdentityKeys.intersect(second.newIdentityKeys).isEmpty())
```

Inspect private state and assert every page with a next handle satisfies
`KnownMinimum.value >= returnedBefore + returnedCount + 1`. The public page
does not serialize returned-before.

Add overloaded functions with the same FQ name, kind, file, and containing type
but different start offsets; prove the anchor selects only the requested
declaration. Add dispatcher scenarios for each endpoint covering resolved, not
found, identity mismatch, unsupported subject kind, missing capability, and
malformed backend payload. Exercise the callers/callees, implementations, and
hierarchy rows of ADR 0022's command-kind matrix; each unsupported pair
preserves the verified subject and invokes no candidate provider.
Add a schema tripwire proving the compatibility-only ambiguity variant is
absent from anchored relationship responses. Assert that a missing capability
returns the exact ADR 0022 degraded code and no raw backend query is issued.

Force page one to stop within the second BFS parent, inside one incoming
reference stream, inside one outgoing lexical walk, and inside one subtype
class-index stream. Resume from the returned family state and prove page two
uses the preserved frontier, visited identities, and exact provider
continuation without replay or overlap.

Build an outgoing function containing calls in nested `if`/`when`/`try`
blocks, a local property initializer, a local function, a local class method,
and a lambda. Assert lexical DFS includes the nested-block and initializer
calls, excludes the nested function/class/lambda bodies, and stores only a
root-to-current child-index stack plus next-reference index. Break a page
immediately before and after an excluded local declaration; resume under the
same generation and prove page two starts at the exact next owned call without
duplicate, omission, or PSI/smart-pointer state.

Instrument fake call/type candidate providers with an atomic visit counter.
Seed 20,000 incoming references, a declaration with 20,000 outgoing PSI
candidates, and 20,000 direct inheritor keys. For a four-result page, assert the
provider stops at the declared visit/state cap before returning a batch. Cover
the cap and cap-plus-one cases. Cap-plus-one must return a family budget outcome
with no records, page claim, or retained partial provider state; at-cap input
must sort and page the complete admitted snapshot. Add tripwires for
`FileTypeIndex.getFiles(...).toList()`, `ReferencesSearch.findAll`, and
inheritor `findAll()`.

With a fake clock and semantic-generation provider, model store claim as
retained, retained-expired, or absent. Assert backend A issues a token and fresh
backend B classifies it exactly like a never-issued random canonical UUID:
cursor-invalid `UNKNOWN_HANDLE`, zero provider work. Replay and capacity
eviction have the same absent outcome. Assert a recognized state with changed
generation is cursor-stale before provider work; retain `EXPIRED` only when the
typed claim observes the expired entry before removal. Wrong family/query are
invalid, malformed syntax fails request parsing, and the 16,384-entry ceiling
is the exact family state-budget outcome. Queue a PSI write before a
continuation and during its provider
call: generation validation, provider work, and next-state commit share one
read action, so the old page is rejected or the write waits until after commit.
Assert `ObservedAnalysisBackend` delegates each handle exactly once, preserves
the typed result, and records the matching `KastBackendOperation` once.

- [ ] **Step 2: Run focused Kotlin tests and confirm RED**

Run:

```console
./gradlew :analysis-api:test :analysis-server:test :backend-shared:test :backend-idea:test --no-daemon
```

Expected: FAIL because the endpoints, outcome variants, and page-aware engine
contracts do not exist.

- [ ] **Step 3: Extract callers and add closed skill contracts**

Move materially edited public caller request/query/response roots out of
`SkillContracts.kt`. Direct response variants stay in the response root file.
Every request carries `KastExactSymbolSelector(fqName, declarationFile,
declarationStartOffset, kind, containingType)`. Parse the file through the #341
workspace path boundary and the offset through `NonNegativeInt`; the parsed
selector stores the canonical file path. Each response root owns its own closed
degraded-reason enum; do not create a shared degradation vocabulary:

```kotlin
sealed interface KastHierarchyResponse {
    data class Available(
        val subject: SymbolIdentity,
        val records: List<TypeHierarchyRelation>,
        val page: RelationTraversalPageInfo,
    ) : KastHierarchyResponse

    data class SubjectNotFound(val selector: KastExactSymbolSelector) : KastHierarchyResponse
    data class SubjectIdentityMismatch(
        val selector: KastExactSymbolSelector,
        val actual: SymbolIdentity,
    ) : KastHierarchyResponse
    data class UnsupportedSubjectKind(
        val selector: KastExactSymbolSelector,
        val subject: SymbolIdentity,
    ) : KastHierarchyResponse
    data class Degraded(
        val selector: KastExactSymbolSelector,
        val subject: SymbolIdentity,
        val reason: KastHierarchyDegradedReason,
    ) : KastHierarchyResponse
    data class CursorStale(
        val selector: KastExactSymbolSelector,
        val reason: RelationCursorStaleReason,
    ) : KastHierarchyResponse
    data class CursorInvalid(
        val selector: KastExactSymbolSelector,
        val reason: RelationCursorInvalidReason,
    ) : KastHierarchyResponse
}
```

Use family-specific record types so call sites, implementation declarations,
and hierarchy depth are required by construction rather than nullable fields.
Operational failures throw the existing structured API exception whose code is
closed at the protocol boundary; do not add a stringly failure result variant.

- [ ] **Step 4: Implement deterministic bounded traversal and dispatch**

Resolve the selector's file/start offset directly, then verify FQ name, kind,
and containing type. A different resolved declaration supplies the non-null
mismatch actual; no declaration is subject-not-found. Do not invoke workspace
symbol search. Apply the closed family subject-kind matrix next and return
`UNSUPPORTED_SUBJECT_KIND` before provider/index calls or state creation. Add
`RelationCandidateVisitBudget` with a per-page maximum
derived from the result limit and capped at 4,096 visits. Start a family state
when no handle is supplied; otherwise claim it through the backend-owned
`ServerHeldContinuationStore` as retained, retained-expired, or absent. Absent
always maps to invalid `UNKNOWN_HANDLE`; only retained generation mismatch and
retained expiry map stale. In one `timedReadAction`, validate family,
normalized query fingerprint, `PsiModificationTracker.modificationCount`, TTL,
and state budget, run the provider, and commit the next state. The call/type
resolver interfaces accept the current pure-data provider continuation and
remaining visit budget and return
`RelationCandidateBatch(records, consumedEvidence, visitedCandidateCount,
nextProviderContinuation, exhaustive)` before the engines build public records.

Incoming calls reuse `IdeaBoundedReferenceProvider`: stream
`FileTypeIndex.processFiles` only through the state cap plus one. Cap-plus-one
stops enumeration and returns the family budget outcome without records, page
claims, or retained partial state. At or below the cap, sort the complete
bounded path snapshot and use `PsiReferenceScanner` in lexical offset order.
Outgoing evidence uses `IdeaOutgoingLexicalDfsProvider`. Starting at the
selected declaration body, it walks PSI children depth-first in lexical order
and persists a bounded pure-data root-to-current child-index stack plus the
next reference index. It descends into nested blocks and local property
initializers, skips nested functions/classes/objects/accessors/lambdas, and
rehydrates the stack only after the same read action has validated the stored
generation. It never stores PSI or restarts the declaration walk on page two.
`IdeaBoundedInheritorProvider` uses
`ClassInheritorsSearch.search(..., checkDeep = false).forEach` through the
direct-inheritor cap plus one and applies the same no-partial-state overflow
rule. At or below the cap, it canonicalizes the complete anchor snapshot
inside the same read action and only then sorts. It never calls `findAll()`.
Calls flatten
breadth-first using `(depth, parent identity, related identity, call-site)`;
implementations and hierarchy use the same canonical identity order. Persist
the updated frontier, visited identities, provider continuation, consumed
evidence, and returned-before proof behind the next backend handle. Retain
cycle/timeout/max-depth/candidate-budget/state-budget evidence, and return
`ResultCardinality.Exact` only when exhausted; otherwise return
`KnownMinimum`, including the plus-one lower bound whenever a next handle
exists. Map missing `CALL_HIERARCHY`, `IMPLEMENTATIONS`, and `TYPE_HIERARCHY`
capabilities to the corresponding response family's reason enum.

Do not expose a separately callable `semanticWorkspaceGeneration()` method:
that would reintroduce a race between server validation and provider work. IDEA
reads the modification count inside the relationship read action. The store
never retains PSI, smart pointers, or analysis sessions—only canonical anchors,
identities, frontier records, and provider continuation. Update
`backend-idea/AGENTS.md` with sole state ownership and atomicity gates;
`analysis-server/AGENTS.md` explicitly remains transport-only.

- [ ] **Step 5: Run Kotlin tests GREEN**

Run:

```console
./gradlew :analysis-api:test :analysis-server:test :backend-shared:test :backend-idea:test --no-daemon
```

Expected: BUILD SUCCESSFUL with no unchecked casts and no additional public
top-level type left in `SkillContracts.kt`.

- [ ] **Step 6: Commit the semantic endpoints**

```console
git add analysis-api analysis-server backend-shared backend-idea
git commit -m "feat: add identity-first relationship endpoints"
```

### Task 5: Rust orchestration, query-bound tokens, and closed projections

**Files:**

- Create: `cli-rs/src/agent/relations.rs`
- Create: `cli-rs/src/agent/projection/relations.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent/projection/view.rs`
- Modify: `cli-rs/src/agent/projection/symbol.rs`
- Modify: `cli-rs/src/agent/AGENTS.md`
- Review unchanged in this task: `cli-rs/Cargo.toml` (Task 7 owns the
  test-only tokenizer dependency)
- Review unchanged in this task: `cli-rs/Cargo.lock` (Task 7 owns its locked
  resolution)
- Modify: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`
- Modify: `index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStoreTest.kt`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`

**Interfaces:**

- Consumes: Task 1 arguments, Kotlin typed endpoint responses, ADR 0020 view
  modes, and #337 relationship projection helpers.
- Produces: `AgentRelationPageToken`, family request runners, closed result
  records, one reusable anchored exact identity projection, typed
  unsupported-kind/degraded/stale/invalid projections, and query-composition
  proof.

- [ ] **Step 1: Add RED end-to-end scripted-backend tests**

Script exact identities and each internal method. Resolve `sample.Service`
once through `agent symbol`, deserialize the returned `identity` object, assert
it contains FQ name, kind, canonical declaration file, declaration start
offset, and optional containing type, and feed those exact values unchanged to
all six commands. In a separate normalization case, replace only the canonical
file argument with workspace-relative `--declaration-file src/Service.kt` under
explicit `--workspace-root`. Both forms must send the same normalized canonical
path, offset, optional hard assertions, limit `4`, no first-page traversal
handle, fixed call direction, and depth.
Assert no request uses `symbol/query`, lexical mode, or a public raw method.
Add an indexed exact fallback candidate with no declaration offset and assert
exact lookup returns `IDENTITY_ANCHOR_UNAVAILABLE`, not a partial `RESOLVED`
identity.

Add a first references page with a canonical UUID `ReferencePageToken`, capture
the public token, invoke page two, and assert the identical opaque value reaches
Kotlin with no overlap. Assert the JSON and Rust payload contain no source,
provider offset, returned-before, query, or generation fields. Reuse that token
with a different anchor, workspace, relation, and depth; each must return
`RELATION_PAGE_TOKEN_MISMATCH` before the scripted backend receives a request.

For calls and hierarchy, return a canonical opaque `rth1` handle, capture the
public token, and assert page two sends the identical handle without exposing
frontier state. Script stale and invalid response variants and assert they
preserve selector/reason. Script a degraded response and assert selector plus
verified subject survive projection. Script each family's
`UNSUPPORTED_SUBJECT_KIND` and assert selector/subject survive while a
wrong-family unsupported-kind shape fails closed. A response containing a
primitive failure code or omitting required identity proof must fail closed.

- [ ] **Step 2: Run the Rust relation test and confirm RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke
```

Expected: FAIL because execution and relation projection are not wired.

- [ ] **Step 3: Implement the exact public token format**

Serialize tokens as five dot-separated ASCII fields:

```text
krp1.<relation-kind>.<24-lowercase-hex-sha256-prefix>.<payload-tag>.<payload>
```

The SHA-256 input is newline-delimited canonical workspace root, relation
kind, FQ symbol, canonical declaration file/start offset, optional kind and
containing type,
include-declaration choice, fixed direction, depth, and page limit. Parsing
must reject unknown versions/kinds/payload tags, non-lowercase hex, malformed
handles, non-canonical impact numbers, impact offsets above 10,000, and a
fingerprint mismatch. Reference payloads contain only the canonical opaque
#337 token; traversal payloads contain only the backend-issued URL-safe handle;
impact payloads contain the offset. This uses
existing `sha2`/`hex` support and deliberately does not add `base64`. Keep the
decoded typed payload and query proof private:

```rust
struct AgentRelationPageToken {
    relation: AgentRelationKind,
    query_fingerprint: [u8; 12],
    cursor: AgentRelationCursor,
}

enum AgentRelationCursor {
    Reference(AgentReferencePageToken),
    Traversal(AgentRelationTraversalHandle),
    Impact(AgentImpactPageOffset),
}
```

- [ ] **Step 4: Implement family runners and closed projection variants**

Dispatch each command to its fixed internal method. Validate endpoint result
tags and required fields before projection. Use family-specific records:

```rust
enum AgentRelationRecord {
    Reference(AgentReferenceRecord),
    Caller(AgentCallRecord),
    Callee(AgentCallRecord),
    Implementation(AgentImplementationRecord),
    Supertype(AgentHierarchyRecord),
    Subtype(AgentHierarchyRecord),
}
```

Project `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_IDENTITY_MISMATCH`,
`UNSUPPORTED_SUBJECT_KIND`, `DEGRADED`, `RELATION_CURSOR_STALE`, and
`RELATION_CURSOR_INVALID` as expected typed results. Reject
`SUBJECT_AMBIGUOUS` if received on an anchored
relationship endpoint. Preserve the selector in every non-available outcome
and the verified subject in degraded output. Reject
wrong-family records, returned-count mismatches, false exact cardinality,
exposed `KNOWN_MINIMUM < returnedCount + 1`, visited-candidate counts over
budget, truncation without a next token, a next token without truncation, a
degraded reason from another response family, a nullable mismatch actual, a
primitive failure-code result, or an omitted selector/subject as
`INVALID_RELATION_RESPONSE`. Map absent `FIND_REFERENCES` to
`REFERENCES_UNAVAILABLE`; an unavailable reference index with IDEA evidence is
an available result, not degradation. Move reusable #337 cardinality,
location, and identity helpers out of symbol-only code without duplicating
their wire validation.

Change exact symbol projection so compiler and trustworthy indexed-exact
results emit the same required identity fields. Reject a resolved compiler
payload missing canonical declaration file/start offset; map an indexed exact
candidate without either anchor component to typed
`IDENTITY_ANCHOR_UNAVAILABLE` rather than synthesizing an offset.

Update `cli-rs/src/agent/AGENTS.md` with `relations.rs`, anchored exact identity,
state-handle wrapping, projection ownership, and the relationship smoke gate.
Confirm this task does not change runtime dependencies. Task 7 intentionally
owns the reviewed `tiktoken-rs` dev-dependency and lockfile update for the
executable compact-output gate.

- [ ] **Step 5: Run focused Rust tests GREEN**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke --test agent_command_surface_smoke
```

Expected: all tests PASS, and detailed view requests retain the same limit,
cursor, and candidate-visit budget as compact requests.

- [ ] **Step 6: Commit public relationship execution**

```console
git add cli-rs/src/agent.rs cli-rs/src/agent cli-rs/tests/agent_relationship_navigation_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs cli-rs/tests/agent_command_surface_smoke.rs
git commit -m "feat: navigate relationships by exact identity"
```

### Task 6: Paged source-impact composition

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent/relations.rs`
- Modify: `cli-rs/src/agent/projection/impact.rs`
- Modify: `cli-rs/src/metrics.rs`
- Modify: `cli-rs/src/metrics_database/model.rs`
- Modify: `cli-rs/src/metrics_database/database.rs`
- Modify: `cli-rs/src/metrics_database/tests.rs`
- Modify: `cli-rs/src/metrics_database/AGENTS.md`
- Modify: `cli-rs/tests/support/metrics.rs`
- Modify: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`

**Interfaces:**

- Consumes: #337's exact count query, bounded impact fetch, typed impact
  projection, and Task 5 public token codec.
- Produces: anchored impact `--page-token`, internal typed offset, stable
  ordering, exact page evidence for production-schema-isolated non-callable
  identities, and conservative typed callable degradation.

- [ ] **Step 1: Add RED 503-node database and CLI paging tests**

Seed one anchored class with an exact production declaration row and 503 impact
nodes in reverse insertion order.
Query offsets 0 and 4 with limit 4. Assert both report total 503, return four
unique nodes, and sort by `(depth, sourcePath, viaTargetFqName, edgeKind)`. Add
three anchored function overloads sharing one FQ name and file, then write them
through the production `SqliteSourceIndexStore`. Prove the production
`(fq_id, prefix_id, filename)` key plus `INSERT OR REPLACE` leaves only one row,
so a declaration count cannot observe three. Select the third compiler anchor
and assert its position request uses exactly its file/start offset, the exact
stored row matches that offset, the function still returns
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`, and no aggregate impact-row query
runs. Add missing/null/mismatched declaration-offset cases that return
`IMPACT_INDEX_IDENTITY_UNAVAILABLE`. A tripwire fails if name resolution or
`symbol/query` runs. At the CLI boundary, capture page one's token and assert
page two has no overlap. Assert a references token and a token for another
declaration anchor fail before position resolution or SQLite.
Add verified `PARAMETER` and `UNKNOWN` identities and assert
`UNSUPPORTED_SUBJECT_KIND` before declaration, count, or impact-row SQL.

- [ ] **Step 2: Run impact tests and confirm RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked metrics_database
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke impact
```

Expected: FAIL because impact has no page-token argument or offset query.

- [ ] **Step 3: Add typed offset to the direct metrics request**

Extend only impact requests with `AgentImpactPageOffset`; other metrics remain
unchanged. In the admitted compiler session, call the internal position resolve
endpoint with the normalized canonical declaration file/start offset. Compare
returned FQ name, kind, containing type, file, and offset with the selector;
not-found and mismatch stop before SQLite. Do not call the FQ-name resolver or
`symbol/query`.

After anchor verification, admit only `CLASS`, `INTERFACE`, `OBJECT`,
`FUNCTION`, or `PROPERTY`; return `UNSUPPORTED_SUBJECT_KIND` for `PARAMETER` or
`UNKNOWN` before SQLite. Then query the production declaration identity by FQ
name, canonical path, non-null declaration offset, and kind. No or mismatched
row returns `IMPACT_INDEX_IDENTITY_UNAVAILABLE`. `FUNCTION` and `PROPERTY`
return `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` even when the row matches,
because the production primary key cannot prove same-file callable isolation.
For `CLASS`, `INTERFACE`, and `OBJECT`, also reject a second stored declaration
row with the same FQ name; this rejects extra rows but is not callable overload
proof. Preserve #337's independent impact-node count query only for an admitted
non-callable subject. The row query must end with:

```sql
ORDER BY depth ASC,
         source_path ASC,
         via_target_fq_name ASC,
         edge_kind ASC
LIMIT ?1 OFFSET ?2
```

Bind `limit + 1` and the validated offset. Drop only the extra continuation
row. Return exact total count, returned count, truncation, and internal next
offset; the projection wraps that offset in the query-bound public token. Never
label FQ-aggregated rows as evidence for one overload.

- [ ] **Step 4: Run impact and projection tests GREEN**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked metrics_database
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke --test agent_result_projection_smoke
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.SqliteSourceIndexStoreTest --no-daemon
```

Expected: all tests PASS; query tracing proves no unbounded impact row select.

- [ ] **Step 5: Commit impact pagination**

```console
git add cli-rs/src/cli/agent.rs cli-rs/src/agent cli-rs/src/metrics.rs cli-rs/src/metrics_database cli-rs/tests/support/metrics.rs cli-rs/tests/agent_relationship_navigation_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStoreTest.kt
git commit -m "feat: page source impact by identity"
```

### Task 7: Contract generation, guidance, budgets, and end-to-end proof

**Files:**

- Modify: `cli-rs/resources/kast-skill/references/commands.json`
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/quickstart.md`
- Modify: `docs/reference/agent-commands.md`
- Modify: `docs/use/inspect-kotlin.md`
- Modify: `cli-rs/tests/packaged_content_smoke.rs`
- Modify: `cli-rs/tests/rpc_catalog_smoke.rs`
- Modify: `cli-rs/tests/repo_resource_smoke.rs`
- Modify: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/Cargo.toml`
- Modify generated dependency resolution: `cli-rs/Cargo.lock`
- Modify generated: `cli-rs/resources/kast-skill/references/commands.yaml`
- Modify generated: `cli-rs/resources/kast-skill/references/requests/`
- Modify generated: `cli-rs/protocol/`

**Interfaces:**

- Consumes: complete command/endpoint behavior from Tasks 1 through 6.
- Produces: generated protocol consistency, installed routing, public evidence
  workflow, and executable high-cardinality budget gates.

- [ ] **Step 1: Add RED package, catalog, and workflow assertions**

Assert packaged guidance names all six public commands, teaches exact symbol
anchored identity first, demonstrates one page continuation, and forbids `rg`,
`grep`, `find`, `agent call`, and unchecked raw position methods in the
positive workflow. Require workspace-relative declaration-file examples under
explicit `--workspace-root`. Add a catalog test requiring
`symbol/implementations` and `symbol/hierarchy` request schemas and every typed
response variant, including non-null identity mismatch, each family's
capability/budget reason, references index-identity/bound-source unavailable,
family unsupported-subject-kind/cursor stale/cursor invalid, state-budget
reached, identity-anchor unavailable, impact index-identity unavailable, and
impact overload granularity unavailable.
Require the
exact symbol schema to place canonical declaration file/start offset in the
single reusable identity object. Reject any references schema that still
accepts only FQ name plus hints or any response schema with a primitive failure
code.

Build high-cardinality public fixtures with 500 references, 250
implementations, a cyclic branching call graph, a deep type hierarchy, and 503
impact nodes. For each compact default assert:

```rust
use tiktoken_rs::cl100k_base;

let tokenizer = cl100k_base().expect("cl100k_base tokenizer must initialize");
assert!(pretty_json.lines().count() <= 120);
assert!(tokenizer.encode_with_special_tokens(&pretty_json).len() <= 1_500);
assert_eq!(result["returnedCount"], 4);
assert_eq!(result["truncated"], true);
assert!(result["nextPageToken"].is_string());
assert!(result["cardinality"]["knownMinimum"].as_u64().unwrap() >= 4 + 1);
```

For compiler families, also assert `visitedCandidateCount` does not exceed the
scripted request budget. Traversal fixtures force a page boundary inside one
provider stream and assert the opaque handle resumes frontier/visited/provider
state without exposing it in public JSON. The scripted backend harness inspects
private state and separately asserts cumulative
`returnedBefore + returnedCount + 1`. Fake-clock/generation cases distinguish
retained expiry/generation staleness from absent
restart/eviction/replay/random-UUID invalidity and also cover source changes,
malformed syntax, and state budget.
Reference fixtures must include one `KNOWN_MINIMUM` page and one exhaustive
`EXACT` page. Rust token decoding must recover only the original opaque
`ReferencePageToken`; backend-store assertions observe `INDEX|IDEA`, provider
position, returned-before, query, subject, and generation privately.

Add `tiktoken-rs = "0.12"` under `[dev-dependencies]` in `cli-rs/Cargo.toml`
and refresh `cli-rs/Cargo.lock` once with the manifest unlocked. This is the
only new dependency and is test-only; the public token codec continues to use
existing runtime dependencies.

- [ ] **Step 2: Run contract tests and confirm RED**

Run:

```console
cargo check --manifest-path cli-rs/Cargo.toml --tests
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke --test rpc_catalog_smoke --test repo_resource_smoke --test agent_relationship_navigation_smoke
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
```

Expected: FAIL because authored catalog/guidance and generated artifacts do not
yet describe the new methods and commands.

- [ ] **Step 3: Update authored catalog and guidance**

Add typed `symbol/implementations` and `symbol/hierarchy` catalog entries.
Update anchored references/callers fields, reusable exact identity, and closed
unsupported-kind/degraded/stale/invalid response variants. Teach this exact
public sequence in docs and the installed skill:

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

Explain typed unsupported-kind/degraded/stale/invalid outcomes, including that
absent UUID handles are invalid while stale requires recognized state;
`EXACT` versus `KNOWN_MINIMUM`, opaque reference source pinning, backend
generation-bound continuation, overload-wide impact limitations, and
page-token reuse without exposing token encoding, backend state, raw RPC method
names, or backend implementation classes.

- [ ] **Step 4: Regenerate every derived contract**

Run the repository-owned generator in write mode, then its check mode:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
```

Regenerate Kotlin-owned OpenAPI and protocol Markdown with the same command
used by #337; do not hand-edit a generated mismatch:

```console
./gradlew generateDocPages --no-daemon
```

- [ ] **Step 5: Run focused docs, package, and budget gates GREEN**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke --test rpc_catalog_smoke --test repo_resource_smoke --test agent_relationship_navigation_smoke
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

Expected: every contract and budget gate PASS.

- [ ] **Step 6: Commit public contracts and generated outputs**

```console
git add cli-rs/Cargo.toml cli-rs/Cargo.lock cli-rs/resources/kast-skill cli-rs/protocol cli-rs/tests docs
git commit -m "docs: teach identity-first relationship navigation"
```

### Task 8: Integration review and full verification

**Files:**

- Review: all files changed since `origin/main`
- Create ignored evidence only: `.agent-turn/issue-339-report.md`

**Interfaces:**

- Consumes: every prior task's independently green commit.
- Produces: one reviewed, clean, PR-ready branch with deterministic verification
  evidence and no overlap with #338/#340 ownership.

- [ ] **Step 1: Review source boundaries and type isolation**

Run:

```console
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
git status --short --branch
```

Inspect every materially edited Kotlin file for one matching non-private
top-level production type. Confirm the diff removes the old
`compiler_symbol_relations` path and its flag reads, updates the IDEA resolvers
that own candidate enumeration, does not touch #338 `workspace_inventory`
files, and introduces no Gradle task/plugin relation claims. Confirm SQL row
queries use production path/offset/kind identity plus impact limit/offset and
never treat FQ row count as callable overload proof. Confirm impact uses
compiler position resolution; references wrap only the landed opaque #337
`ReferencePageToken`; exact INDEX reads include target path/offset; and
call/type compiler requests carry bounded provider continuation plus
candidate-visit/state budgets. Confirm backend generation validation, provider
work, and state commit share one read action; `ObservedAnalysisBackend`
delegates every new contract; `analysis-server` owns no state; scoped
`AGENTS.md` files describe those owners; and `cli-rs/Cargo.toml`/`Cargo.lock`
contain only the reviewed `tiktoken-rs` dev-dependency resolution needed by the
executable compact-output test.

- [ ] **Step 2: Run complete Kotlin verification**

```console
./gradlew test --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run complete Rust verification**

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
```

Expected: all commands exit 0 and clippy emits no warnings.

- [ ] **Step 4: Run generated and documentation verification**

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
.github/scripts/test-macos-installer-contract.sh
zensical build --clean
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 5: Record proof and request independent review**

Write `.agent-turn/issue-339-report.md` with commit SHAs, exact commands,
terminal results, output-budget maxima, the landed #337 cursor/cardinality type
names, backend store TTL/capacity/state maxima, and the known
unprepared-worktree Kast limitation. Ask a fresh reviewer to check anchored
overload identity, exact-target INDEX references, extracted scaffold migration,
opaque reference/traversal tokens, backend-owned query/source/generation
binding, atomic read-action generation/provider/state behavior,
`ObservedAnalysisBackend` delegation, frontier/visited resume behavior,
family-specific typed stale/invalid/degraded outcomes, consistent absent-handle
invalidity, subject-kind admission, plus-one cardinality, resumable outgoing
lexical DFS with nested-declaration exclusion, bounded named IDEA providers,
executable locked token-budget proof, production-schema impact identity and
overload regression, SQL bounds, and #338/#340 isolation.
Repair findings with new focused RED/GREEN commits and rerun affected plus full
gates.

- [ ] **Step 6: Leave the branch clean**

```console
git status --short --branch
git log --oneline --decorate origin/main..HEAD
```

Expected: only the intentionally ignored `.agent-turn` report is outside Git;
the tracked worktree is clean and every issue-owned commit is visible.
