# Identity-first Relationship Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship standalone, compact, identity-first Kotlin relationship
commands with deterministic bounded paging and typed degraded outcomes.

**Architecture:** Exact symbol lookup produces one anchored canonical identity
that five typed relationship commands plus impact consume without overload
re-resolution. Kotlin skill endpoints verify its declaration file/start offset,
runtime resolvers collect deterministic evidence under pre-materialization
candidate budgets, and `analysis-server` preserves call/type frontier state
behind bounded generation/query-bound handles. The Rust CLI wraps finalized
#337 reference cursors, traversal handles, or impact offsets in query-bound page
tokens before projecting closed records. Source impact keeps its Rust/SQLite
path and degrades honestly when its FQ-keyed index cannot isolate an overload.

**Tech Stack:** Kotlin/JVM, JUnit Jupiter, IDEA PSI/search APIs, Rust 2024,
Clap, serde, SHA-256, SQLite/rusqlite, scripted Unix-socket integration tests,
generated JSON Schema/OpenAPI contracts, Markdown/Zensical.

## Global Constraints

- Rebase the implementation branch after issue #337 lands; do not recreate or
  bypass its compact projections, positive reference limit, separate impact
  count query, bounded `limit + 1` fetch, `ResultCardinality.EXACT` /
  `KNOWN_MINIMUM`, or source/evidence/returned reference cursor. The current
  #337 names are provisional; adapt to the landed names without losing fields.
- Use one non-private top-level Kotlin production type per matching file.
- Public commands accept anchored canonical identity only: FQ name,
  declaration file, and declaration start offset, with optional kind and
  containing type assertions. Under explicit `--workspace-root`, accept #341
  workspace-relative declaration paths and normalize/store the canonical path.
  Exact symbol output must expose those fields in one reusable `identity`
  object. Never invoke lexical discovery or accept arbitrary JSON.
- Default relationship limit is 4; valid limits are 1 through 200. Call and
  type hierarchy depth defaults to 1; valid depth is 1 through 8.
- Reference tokens preserve #337 source (`INDEX|IDEA`), evidence offset, and
  returned-before losslessly. Impact tokens preserve a typed SQLite offset;
  both stateless offsets are capped at 10,000. Call/type tokens carry a
  generation/query-bound opaque server handle whose state preserves the BFS
  frontier, visited identities, provider continuation, consumed evidence, and
  returned-before proof. Detailed output never removes result,
  candidate-visit, or state bounds.
- Compact relationship output must remain at or below 120 lines and 1,500
  `cl100k_base` tokens for high-cardinality fixtures.
- Missing capabilities and source-index availability return typed degraded
  outcomes whose codes are a closed enum; reference-index absence with a usable
  IDEA fallback is not degraded, and FQ-aggregated overload impact uses
  `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`. Malformed payloads and operational
  failures remain errors.
- Resolver candidate work is bounded before list materialization. Every
  compiler page reports visited-candidate count, consumed evidence, next
  provider continuation, and exhaustiveness; tests instrument provider visits.
  The traversal store has a 15-minute TTL, at most 1,024 handles per exact
  workspace runtime, and at most 16,384 frontier/visited/provider entries per
  state. Stale and invalid handles are closed typed outcomes.
- Migrate `KastReferencesRequest`, `KastReferencesQuery`, and
  `KastReferencesResponse` to the anchored selector; remove named resolution
  from the endpoint and migrate scaffold/contract fixtures to
  `ReferenceOccurrence`.
- Degraded outcomes preserve selector and verified subject. Cursor stale and
  invalid are separate typed outcomes. Operational failures use structured
  closed error codes, never `Failure(code: String)`.
- Do not edit issue #338's workspace-inventory implementation or use it as
  semantic relation evidence. Do not model issue #340's Gradle task, plugin,
  dependency, or build-logic relations as Kotlin relationships.
- Generated catalog, schema, protocol, and docs artifacts must come from their
  checked-in source owners.
- Update the nearest scoped `AGENTS.md` whenever this work changes public
  command ownership or validation gates. The token formats use existing
  SHA-256/hex plus canonical ASCII and opaque handles; `Cargo.toml` and
  `Cargo.lock` must remain unchanged unless a reviewed implementation proves a
  new dependency is necessary.

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
  remains its own source-bound `INDEX|IDEA` variant.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationTraversalHandle.kt`,
  `RelationCursorStaleReason.kt`, and `RelationCursorInvalidReason.kt` own the
  opaque handle and closed continuation outcomes.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SemanticWorkspaceGeneration.kt`
  owns typed semantic generation evidence.
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RelationTraversalStateStore.kt`
  owns TTL/capacity, query/generation binding, and typed handle lookup.
- Family-specific state owners under `backend-shared/.../hierarchy/` contain
  the BFS frontier, visited identities, provider continuation, and accumulated
  proof; they never expose these internals in a public token.
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
  and closed outcomes; scaffold and backend contract fixtures migrate to
  `ReferenceOccurrence`. Direct sealed variants stay beside their response
  root.

## Test inventory

The implementation is incomplete until every row is executable:

| Gate | Cases |
| --- | --- |
| Public parsing | Commands visible; declaration file/offset required together; #341-relative file accepted; retired symbol flags and old execution path rejected; typed direction exhaustive; depth/limit/token ranges fail in Clap. |
| Identity | One exact output identity object passes unchanged; same-file overloads resolve by file/start offset; optional kind/containing assertions are verified; not-found/mismatch stop before relation work; no discovery method called. |
| References | Anchored `KastReferences*` contracts never call named resolution; scaffold/fixtures consume occurrences; #337 `INDEX|IDEA` source/evidence/returned cursor and `EXACT|KNOWN_MINIMUM` round-trip losslessly; containing symbol and `usageSiteScope` do not conflict; stable ordered pages; 500-record budget fixture. |
| Calls | Incoming/outgoing fixed by command; BFS ordering; depth, result, state handle, and provider-visit bounds; a page boundary inside one parent/provider resumes without revisit; cycle, timeout, state budget, generation staleness, invalid handle, truncation, related/containing identity. |
| Implementations | Interface implementation and class subclass records; exact versus known-minimum cardinality; stateful deterministic bounded provider pages; stale/invalid handles; capability absent degrades. |
| Hierarchy | Supertypes/subtypes/both; depth; cycle; stateful deterministic bounded provider pages; semantic-generation changes invalidate continuation; capability absent degrades. |
| Impact | Compiler position lookup verifies the selected anchor; exact SQL FQ count degrades a selected third overload; unique subject exact count; ordered `limit + 1 offset`; 503 records across non-overlapping pages; missing/incompatible index degrades. |
| Projection | Wrong item family, invalid counts, false exactness, omitted subject/selector, primitive failure code, token/truncation disagreement, and malformed subject fail closed. |
| Contracts | Catalog, schemas, samples, API docs, OpenAPI, packaged content, and public command docs regenerated and checked. |
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
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SemanticWorkspaceGeneration.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolEvidence.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolUnavailableReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferenceOccurrence.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationPageInfo.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationTraversalHandle.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationCursorStaleReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationCursorInvalidReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastExactSymbolSelector.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedExactSymbolSelector.kt`
- Create if #337 does not land it: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ReferenceEvidenceSource.kt`
- Modify after #337 lands: its reference cursor and cardinality owner files under `analysis-api/src/main/kotlin/`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/ReferencesQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferencesResult.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedReferencesQuery.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/RelationshipModelTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- Modify: `analysis-api/AGENTS.md`

**Interfaces:**

- Consumes: #337's `PositiveInt`, `ReferencesQuery.maxResults`, provisional
  `ReferencePageCursor(source, evidenceOffset, returnedBefore)`,
  `ResultCardinality.EXACT|KNOWN_MINIMUM`, and `PageInfo` behavior. Rebase and
  use the landed names if they change.
- Produces: `SymbolIdentity`, closed `ContainingSymbolEvidence`,
  `ReferenceOccurrence`, `RelationPageInfo`, anchored request selector,
  semantic generation, opaque traversal handle, and closed stale/invalid
  reasons. It does not replace or flatten #337's reference cursor and does not
  serialize call/type frontier state into the handle.

- [ ] **Step 1: Write RED model tests for proof states and page invariants**

Add a JSON round-trip test for all containing-symbol variants and invalid page
claims. The expected wire values are:

```kotlin
val known = ContainingSymbolEvidence.Known(
    SymbolIdentity(
        fqName = "sample.Controller.handle",
        kind = SymbolKind.FUNCTION,
        location = Location("/repo/Controller.kt", 10, 16, 2, 5, "handle()"),
    ),
)
val topLevel = ContainingSymbolEvidence.TopLevel
val unavailable = ContainingSymbolEvidence.Unavailable(
    ContainingSymbolUnavailableReason.NO_SEMANTIC_OWNER,
)
```

Round-trip `ReferencePageCursor(INDEX, 7, 4)` and
`ReferencePageCursor(IDEA, 9, 4)` through the shared page model and JSON; source,
evidence offset, and returned-before must survive unchanged. Round-trip an
opaque server-issued `RelationTraversalHandle` and every stale/invalid reason;
reject blank, malformed-version, non-ASCII, and overlong handles at the parser;
the state-store tests reject a syntactically valid client-invented handle as
typed invalid. Test that `RelationPageInfo` rejects returned
count greater than exact cardinality, `KNOWN_MINIMUM` below returned-before plus
page results, a next handle without truncation, truncation without a next
handle, and visited count above the declared candidate budget.

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

data class RelationPageInfo private constructor(
    val cardinality: ResultCardinality,
    val returnedCount: Int,
    val returnedBefore: Int,
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
        ): RelationPageInfo
    }
}

@JvmInline
value class RelationTraversalHandle private constructor(val value: String)

@JvmInline
value class SemanticWorkspaceGeneration private constructor(val value: Long) {
    init { require(value >= 0) }
}
```

Change `ReferencesResult.references` to `List<ReferenceOccurrence>`. Replace
#337's bare locations but preserve `ReferencePageCursor` and
`ResultCardinality` losslessly. Keep `Location.usageSiteScope` and
`ReferencesQuery.includeUsageSiteScope` as the optional structural-scope
contract; `ReferenceOccurrence.containingSymbol` is separate semantic identity
evidence. Record the provisional #337 dependency and shared relationship
contract in `analysis-api/AGENTS.md`.

`KastExactSymbolSelector` is the serializable request DTO. Its parsed form owns
`NonBlankString`, `NormalizedPath`, `NonNegativeInt`, optional typed kind, and
optional non-blank containing type. `RelationTraversalHandle` has no public
client factory: the server issues canonical `rth1` handles and the request
boundary only parses them. Frontier and visited state do not appear in this
host-agnostic wire model.

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
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/contract/skill/SymbolQuerySchemaContractTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/AnalysisBackendContractFixture.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Modify after #337 lands: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/ReferenceIndexLookup.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`

**Interfaces:**

- Consumes: `ReferenceOccurrence`, `ContainingSymbolEvidence`, #337's positive
  reference limit, source-bound `ReferencePageCursor`, `ResultCardinality`, and
  optional `includeUsageSiteScope`, plus the anchored exact selector from Task
  2.
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
assertEquals((0 until 4).toList(), first.references.map { it.location.startLine })
assertEquals((4 until 8).toList(), second.references.map { it.location.startLine })
assertEquals(ResultCardinality.KnownMinimum(5), first.cardinality)
val firstCursor = ReferencePageCursor.parse(first.page?.nextPageToken)
assertEquals(ReferenceEvidenceSource.IDEA, firstCursor.source)
assertEquals(5, firstCursor.evidenceOffset.value)
assertEquals(4, firstCursor.returnedBefore.value)
```

Add an exhaustive four-reference fixture that reports
`ResultCardinality.Exact(4)` and an indexed fixture whose authoritative count
also reports exact. Add two functions sharing FQ name, kind, file, and
containing type but with different declaration offsets; pass the second exact
anchor and prove the raw backend receives only that file/offset while a
`resolveNamedSymbol` tripwire remains untouched. At the dispatcher boundary,
assert `maxResults=4` and a source/evidence/returned cursor become typed query
values. Malformed cursors return the relationship usage error without calling
the fake backend. If page one binds IDEA and page two finds only the index
source, assert
`REFERENCE_CURSOR_SOURCE_UNAVAILABLE`; it must not restart at index offset.
Assert every available/degraded/not-found/mismatch/source-unavailable response
preserves the selector or verified subject. Update scaffold and backend
contract fixtures to assert occurrences and containing-symbol evidence rather
than `List<Location>`.

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```console
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.KastPluginBackendContractTest --no-daemon
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherTest --no-daemon
```

Expected: FAIL because references still expose bare locations and no
containing-symbol evidence.

- [ ] **Step 3: Collect proof while PSI is owned and preserve bounded work**

Use #337's selected reference source and cursor as authority. Ask that source
for at most the evidence needed to return `limit + 1` usable occurrences; stop
at its candidate-visit budget before materializing an unbounded collection.
Map those bounded usages to `ReferenceOccurrence` in the IDEA read-action.
Convert the nearest supported containing declaration to `SymbolIdentity`; emit
`TopLevel` only after proving no containing declaration, and `Unavailable` when
a declaration exists but semantic conversion fails. Preserve separately
requested `Location.usageSiteScope` on the same bounded PSI pass. Sort by
normalized file path, start offset, end offset, and known containing FQ name.
Return `EXACT` only after source exhaustion or from an authoritative exact
count for the same query; otherwise preserve `KNOWN_MINIMUM`. Drop only the
extra continuation occurrence.

Change `KastReferencesRequest` and `KastReferencesQuery` to carry
`KastExactSymbolSelector`. Replace success/failure with the shared closed
available, subject-not-found, identity-mismatch, degraded, and
reference-cursor-source-unavailable variants. Resolve the
canonical declaration file/start offset directly, verify every identity field,
and delete the endpoint's `resolveNamedSymbol` call. Operational failures
remain structured RPC errors. Migrate `KastScaffoldReferences` and its fixtures
to `ReferenceOccurrence` so no adapter discards containing-symbol proof.

- [ ] **Step 4: Run reference tests GREEN and inspect diagnostics**

Run:

```console
./gradlew :analysis-server:test :backend-idea:test --no-daemon
```

Expected: BUILD SUCCESSFUL. If the IntelliJ-prepared primary workspace can
analyze the changed files with the running binary, also run typed Kast
diagnostics there; an unprepared implementation worktree must report the
plugin-metadata limitation and must not run `kast setup`.

- [ ] **Step 5: Commit reference evidence**

```console
git add analysis-api analysis-server backend-idea
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
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationDegradedCode.kt`
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
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/TypeTraversalState.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/RelationTraversalStateBudget.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/CallHierarchyEngine.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/TypeHierarchyEngine.kt`
- Create: `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/hierarchy/RelationshipPagingTest.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaCallEdgeResolver.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaTypeEdgeResolver.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaRelationCandidateBudgetTest.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Create: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RelationTraversalStateStore.kt`
- Create: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/RelationTraversalStateStoreTest.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`
- Modify: `analysis-server/AGENTS.md`

**Interfaces:**

- Consumes: anchored selector outcomes, opaque `RelationTraversalHandle`,
  positive limits, `SymbolIdentity`, semantic workspace generation, backend
  capability enums, and full-fidelity raw hierarchy results.
- Produces: internal `symbol/implementations` and `symbol/hierarchy` methods;
  statefully paged flat call/implementation/hierarchy records; typed available,
  subject-not-found, identity-mismatch, degraded, cursor-stale, and
  cursor-invalid variants; operational faults remain RPC errors. Exact-lookup
  ambiguity does not leak into anchored relationship responses.

- [ ] **Step 1: Add RED model and traversal tests**

Create balanced and cyclic call/type graphs whose insertion order differs from
canonical order. Assert breadth-first call pages and identity-sorted
implementation/type pages. The page window assertion is:

```kotlin
val visitBudget = RelationCandidateVisitBudget.from(65)
val first = engine.start(
    limit = PositiveInt.from(4),
    candidateVisitBudget = visitBudget,
    stateBudget = RelationTraversalStateBudget.default(),
)
val second = engine.resume(
    state = requireNotNull(first.nextState),
    limit = PositiveInt.from(4),
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

Add overloaded functions with the same FQ name, kind, file, and containing type
but different start offsets; prove the anchor selects only the requested
declaration. Add dispatcher scenarios for each endpoint covering resolved, not
found, identity mismatch, missing capability, and malformed backend payload.
Add a schema tripwire proving the compatibility-only ambiguity variant is
absent from anchored relationship responses. Assert that a missing capability
returns the exact ADR 0022 degraded code and no raw backend query is issued.

Force page one to stop within the second BFS parent, inside one incoming
reference stream, inside one outgoing lexical walk, and inside one subtype
class-index stream. Resume from the returned family state and prove page two
uses the preserved frontier, visited identities, and exact provider
continuation without replay or overlap.

Instrument fake call/type candidate providers with an atomic visit counter.
Seed 20,000 incoming references, a declaration with 20,000 outgoing PSI
candidates, and 20,000 direct inheritor keys. For a four-result page, assert the
provider stops at the declared visit budget before returning a batch. Add a
tripwire provider whose `findAll`/unbounded-list method throws if called.

With a fake clock and semantic-generation provider, assert 15-minute expiry,
1,024-handle eviction, runtime-generation replacement, PSI-generation change,
wrong family, wrong query, malformed handle, and the 16,384-entry state ceiling
map to the exact stale, invalid, or state-budget outcomes before provider work.

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
selector stores the canonical file path. Use the same closed outcome vocabulary
for all three families:

```kotlin
sealed interface KastHierarchyResponse {
    data class Available(
        val subject: SymbolIdentity,
        val records: List<TypeHierarchyRelation>,
        val page: RelationPageInfo,
    ) : KastHierarchyResponse

    data class SubjectNotFound(val selector: KastExactSymbolSelector) : KastHierarchyResponse
    data class SubjectIdentityMismatch(
        val selector: KastExactSymbolSelector,
        val actual: SymbolIdentity?,
    ) : KastHierarchyResponse
    data class Degraded(
        val selector: KastExactSymbolSelector,
        val subject: SymbolIdentity,
        val code: RelationDegradedCode,
        val capability: ReadCapability,
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
and containing type. Do not invoke workspace symbol search. Add
`RelationCandidateVisitBudget` with a per-page maximum derived from the result
limit and capped at 4,096 visits. Start a family state when no handle is
supplied; otherwise load it through `RelationTraversalStateStore` and validate
family, normalized query fingerprint, semantic generation, TTL, and state
budget before provider work. The call/type resolver interfaces accept the
current provider continuation and remaining visit budget and return
`RelationCandidateBatch(records, consumedEvidence, visitedCandidateCount,
nextProviderContinuation, exhaustive)` before the engines build public records.

`IdeaCallEdgeResolver` must stop `ReferencesSearch` processing at the budget;
incoming evidence uses canonical file/offset order and outgoing evidence uses
lexical declaration offsets without first walking the complete declaration.
`IdeaTypeEdgeResolver` iterates canonical `(fqName, kind, file, offset)` class
index keys with a stoppable processor and never calls `findAll()`. Calls flatten
breadth-first using `(depth, parent identity, related identity, call-site)`;
implementations and hierarchy use the same canonical identity order. Persist
the updated frontier, visited identities, provider continuation, consumed
evidence, and returned-before proof behind the next server handle. Retain
cycle/timeout/max-depth/candidate-budget/state-budget evidence, and return
`ResultCardinality.Exact` only when exhausted; otherwise return
`KnownMinimum`. Map missing `CALL_HIERARCHY`, `IMPLEMENTATIONS`, and
`TYPE_HIERARCHY` capabilities to their typed degraded codes.

Expose typed `semanticWorkspaceGeneration()` evidence from every backend.
IDEA reads the monotonic compiler/PSI modification count; test fixtures use a
controllable generation. The state store never retains PSI elements, only
canonical anchors, identities, frontier records, and provider continuation.
Record state-store ownership, typed stale/invalid mapping, and its focused gate
in `analysis-server/AGENTS.md`.

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
- Review unchanged: `cli-rs/Cargo.toml`
- Review unchanged: `cli-rs/Cargo.lock`
- Modify: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`

**Interfaces:**

- Consumes: Task 1 arguments, Kotlin typed endpoint responses, ADR 0020 view
  modes, and #337 relationship projection helpers.
- Produces: `AgentRelationPageToken`, family request runners, closed result
  records, one reusable anchored exact identity projection, typed
  degraded/stale/invalid projections, and query-composition proof.

- [ ] **Step 1: Add RED end-to-end scripted-backend tests**

Script exact identities and each internal method. Resolve `sample.Service`
once through `agent symbol`, deserialize the returned `identity` object, assert
it contains FQ name, kind, canonical declaration file, declaration start
offset, and optional containing type, and feed those exact values unchanged to
all five commands. In a separate normalization case, replace only the canonical
file argument with workspace-relative `--declaration-file src/Service.kt` under
explicit `--workspace-root`. Both forms must send the same normalized canonical
path, offset, optional hard assertions, limit `4`, no first-page traversal
handle, fixed call direction, and depth.
Assert no request uses `symbol/query`, lexical mode, or a public raw method.
Add an indexed exact fallback candidate with no declaration offset and assert
exact lookup returns `IDENTITY_ANCHOR_UNAVAILABLE`, not a partial `RESOLVED`
identity.

Add a first references page with
`ReferencePageCursor(IDEA, evidenceOffset=7, returnedBefore=4)`, capture the
public token, invoke page two, and assert the identical source/evidence/returned
fields reach Kotlin with no overlap. Reuse that token with a different anchor,
workspace, relation, and depth; each must return
`RELATION_PAGE_TOKEN_MISMATCH` before the scripted backend receives a request.

For calls and hierarchy, return a canonical opaque `rth1` handle, capture the
public token, and assert page two sends the identical handle without exposing
frontier state. Script stale and invalid response variants and assert they
preserve selector/reason. Script a degraded response and assert selector plus
verified subject survive projection. A response containing a primitive failure
code or omitting required identity proof must fail closed.

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
handles, non-canonical numbers, evidence offsets above 10,000, and a fingerprint
mismatch. Reference payloads encode `INDEX|IDEA`, `evidenceOffset`, and
`returnedBefore` in canonical order; traversal payloads contain only the
server-issued URL-safe handle; impact payloads contain the offset. This uses
existing `sha2`/`hex` support and deliberately does not add `base64`. Keep the
decoded typed payload and query proof private:

```rust
struct AgentRelationPageToken {
    relation: AgentRelationKind,
    query_fingerprint: [u8; 12],
    cursor: AgentRelationCursor,
}

enum AgentRelationCursor {
    Reference(AgentReferencePageCursor),
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
`DEGRADED`, `RELATION_CURSOR_STALE`, and `RELATION_CURSOR_INVALID` as expected
typed results. Reject `SUBJECT_AMBIGUOUS` if received on an anchored
relationship endpoint. Preserve the selector in every non-available outcome
and the verified subject in degraded output. Reject
wrong-family records, returned-count mismatches, false exact cardinality,
lossy `KNOWN_MINIMUM`, visited-candidate counts over budget, truncation without
a next token, a next token without truncation, a primitive failure-code result,
or an omitted selector/subject as
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
Confirm `Cargo.toml` and `Cargo.lock` are absent from the implementation diff;
adding a token dependency requires a new reviewed plan amendment.

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
- Modify: `cli-rs/tests/support/metrics.rs`
- Modify: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`

**Interfaces:**

- Consumes: #337's exact count query, bounded impact fetch, typed impact
  projection, and Task 5 public token codec.
- Produces: anchored impact `--page-token`, internal typed offset, stable
  ordering, exact page evidence for unique FQ identities, and typed overload
  granularity degradation.

- [ ] **Step 1: Add RED 503-node database and CLI paging tests**

Seed one anchored declaration with 503 impact nodes in reverse insertion order.
Query offsets 0 and 4 with limit 4. Assert both report total 503, return four
unique nodes, and sort by `(depth, sourcePath, viaTargetFqName, edgeKind)`. Add
three anchored overloads sharing one FQ name and seed aggregate edges. Select
the third overload and assert the compiler position request uses exactly its
file/start offset, the exact SQL FQ declaration count returns three,
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` names the aggregate FQ identity, and
no impact-row query runs. A tripwire fails the test if name resolution or
`symbol/query` runs. At the CLI boundary, capture page one's token and assert
page two has no overlap. Assert a references token and a token for another
declaration anchor fail before position resolution or SQLite.

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

After anchor verification, issue a separate exact SQL declaration-count query
for the FQ name. Count zero is incompatible index evidence; count greater than
one returns overload-granularity degradation without reading impact rows; count
one may continue. Preserve #337's independent impact-node count query for the
unique subject. The row query must end with:

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
```

Expected: all tests PASS; query tracing proves no unbounded impact row select.

- [ ] **Step 5: Commit impact pagination**

```console
git add cli-rs/src/cli/agent.rs cli-rs/src/agent cli-rs/src/metrics.rs cli-rs/src/metrics_database cli-rs/tests/support/metrics.rs cli-rs/tests/agent_relationship_navigation_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs
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
- Modify generated: `cli-rs/resources/kast-skill/references/commands.yaml`
- Modify generated: `cli-rs/resources/kast-skill/references/requests/`
- Modify generated: `cli-rs/protocol/`

**Interfaces:**

- Consumes: complete command/endpoint behavior from Tasks 1 through 6.
- Produces: generated protocol consistency, installed routing, public evidence
  workflow, and executable high-cardinality budget gates.

- [ ] **Step 1: Add RED package, catalog, and workflow assertions**

Assert packaged guidance names all five public commands, teaches exact symbol
anchored identity first, demonstrates one page continuation, and forbids `rg`,
`grep`, `find`, `agent call`, and unchecked raw position methods in the
positive workflow. Require workspace-relative declaration-file examples under
explicit `--workspace-root`. Add a catalog test requiring
`symbol/implementations` and `symbol/hierarchy` request schemas and every typed
response variant, including identity mismatch, references unavailable,
reference-cursor-source unavailable, traversal-cursor stale/invalid,
state-budget reached, identity-anchor unavailable, and impact overload
granularity unavailable. Require the
exact symbol schema to place canonical declaration file/start offset in the
single reusable identity object. Reject any references schema that still
accepts only FQ name plus hints or any response schema with a primitive failure
code.

Build high-cardinality public fixtures with 500 references, 250
implementations, a cyclic branching call graph, a deep type hierarchy, and 503
impact nodes. For each compact default assert:

```rust
assert!(pretty_json.lines().count() <= 120);
assert!(cl100k_base().encode_with_special_tokens(&pretty_json).len() <= 1_500);
assert_eq!(result["returnedCount"], 4);
assert_eq!(result["truncated"], true);
assert!(result["nextPageToken"].is_string());
```

For compiler families, also assert `visitedCandidateCount` does not exceed the
scripted request budget. Traversal fixtures force a page boundary inside one
provider stream and assert the opaque handle resumes frontier/visited/provider
state without exposing it in public JSON. Fake-clock/generation cases cover
expiry, eviction, source changes, malformed handles, and state budget.
Reference fixtures must include one `KNOWN_MINIMUM` page and one exhaustive
`EXACT` page; token decoding in the scripted backend must observe the original
`INDEX|IDEA` source/evidence/returned cursor fields.

- [ ] **Step 2: Run contract tests and confirm RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke --test rpc_catalog_smoke --test repo_resource_smoke --test agent_relationship_navigation_smoke
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
```

Expected: FAIL because authored catalog/guidance and generated artifacts do not
yet describe the new methods and commands.

- [ ] **Step 3: Update authored catalog and guidance**

Add typed `symbol/implementations` and `symbol/hierarchy` catalog entries.
Update anchored references/callers fields, reusable exact identity, and closed
degraded/stale/invalid response variants. Teach this exact public sequence in
docs and the installed skill:

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

Explain typed degraded/stale/invalid outcomes, `EXACT` versus `KNOWN_MINIMUM`,
reference source pinning, generation-bound traversal continuation,
overload-wide impact limitations, and page-token reuse without exposing token
encoding, server state, raw RPC method names, or backend implementation
classes.

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
git add cli-rs/resources/kast-skill cli-rs/protocol cli-rs/tests docs
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
queries carry exact FQ declaration count plus impact limit/offset; impact uses
compiler position resolution; references use the landed #337 cursor; and
call/type compiler requests carry bounded provider continuation plus
candidate-visit/state budgets. Confirm `cli-rs/src/agent/AGENTS.md` describes
the new owners and `cli-rs/Cargo.toml`/`Cargo.lock` remain unchanged.

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
names, traversal store TTL/capacity/state maxima, and the known
unprepared-worktree Kast limitation. Ask a fresh reviewer to check anchored
overload identity, anchored references/scaffold migration, lossless reference
tokens, opaque traversal-handle query/generation binding, frontier/visited
resume behavior, typed stale/invalid/degraded outcomes,
cardinality/truncation truthfulness, capability versus fallback degradation,
pre-materialization candidate bounds, compiler-position impact verification,
SQL bounds, and #338/#340 isolation.
Repair findings with new focused RED/GREEN commits and rerun affected plus full
gates.

- [ ] **Step 6: Leave the branch clean**

```console
git status --short --branch
git log --oneline --decorate origin/main..HEAD
```

Expected: only the intentionally ignored `.agent-turn` report is outside Git;
the tracked worktree is clean and every issue-owned commit is visible.
