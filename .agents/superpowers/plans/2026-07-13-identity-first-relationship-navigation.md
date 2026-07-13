# Identity-first Relationship Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship standalone, compact, identity-first Kotlin relationship
commands with deterministic bounded paging and typed degraded outcomes.

**Architecture:** Exact symbol lookup produces a canonical selector that five
typed relationship commands consume. Kotlin skill endpoints translate that
selector to compiler-owned positions, runtime backends collect deterministic
bounded evidence, and the Rust CLI validates a query-bound page token before
projecting closed per-family records. Source impact keeps its Rust/SQLite path
and adopts the same public paging contract.

**Tech Stack:** Kotlin/JVM, JUnit Jupiter, IDEA PSI/search APIs, Rust 2024,
Clap, serde, SHA-256, SQLite/rusqlite, scripted Unix-socket integration tests,
generated JSON Schema/OpenAPI contracts, Markdown/Zensical.

## Global Constraints

- Rebase the implementation branch after issue #337 lands; do not recreate or
  bypass its compact projections, positive reference limit, internal reference
  page offset, separate impact count query, or bounded `limit + 1` fetch.
- Use one non-private top-level Kotlin production type per matching file.
- Public commands accept exact canonical identity only. They never invoke
  lexical discovery and never accept raw file offsets or arbitrary JSON.
- Default relationship limit is 4; valid limits are 1 through 200. Call and
  type hierarchy depth defaults to 1; valid depth is 1 through 8.
- Public page offsets are capped at 10,000. Detailed output never removes the
  explicit work limit or page offset.
- Compact relationship output must remain at or below 120 lines and 1,500
  `cl100k_base` tokens for high-cardinality fixtures.
- Missing capabilities and source-index availability return typed degraded
  outcomes whose codes are a closed enum; malformed payloads and operational
  failures remain errors.
- Do not edit issue #338's workspace-inventory implementation or use it as
  semantic relation evidence. Do not model issue #340's Gradle task, plugin,
  dependency, or build-logic relations as Kotlin relationships.
- Generated catalog, schema, protocol, and docs artifacts must come from their
  checked-in source owners.

---

## File structure

New and materially changed files have one responsibility:

- `cli-rs/src/agent/relations.rs` owns exact selector orchestration, relation
  request construction, public token validation, and degraded mapping.
- `cli-rs/src/agent/projection/relations.rs` owns closed compact, field, count,
  verbose, and explain relation projections.
- `cli-rs/tests/agent_relationship_navigation_smoke.rs` owns CLI composition,
  paging, degradation, and output-budget proof.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferenceOccurrence.kt`
  owns one reference occurrence.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolEvidence.kt`
  owns the closed containing declaration evidence variants.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolUnavailableReason.kt`
  owns the closed reasons that semantic containment could not be reported.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SymbolIdentity.kt`
  owns lightweight compiler identity.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationPageInfo.kt`
  and `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/RelationPageOffset.kt`
  own shared internal paging evidence and validation.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsRequest.kt`
  and `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastImplementationsResponse.kt`
  own identity-first implementation lookup.
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyRequest.kt`
  and `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastHierarchyResponse.kt`
  own identity-first type hierarchy lookup.
- Existing caller/reference skill contract owners are extracted from
  `SkillContracts.kt` when materially edited, preserving direct sealed variants
  beside their response root.

## Test inventory

The implementation is incomplete until every row is executable:

| Gate | Cases |
| --- | --- |
| Public parsing | Commands visible; retired symbol flags rejected; typed direction exhaustive; depth/limit/token ranges fail in Clap. |
| Identity | FQ identity passes unchanged; optional kind/file/containing hard constraints preserved; not-found/ambiguous stop before relation work; no discovery method called. |
| References | Containing symbol known/top-level/unavailable; include-declaration explicit; stable ordered pages; 500-record budget fixture. |
| Calls | Incoming/outgoing fixed by command; BFS ordering; depth and total limit; cycle, timeout, truncation, page continuation, related/containing identity. |
| Implementations | Interface implementation and class subclass records; exhaustion versus lower bound; deterministic pages; capability absent degrades. |
| Hierarchy | Supertypes/subtypes/both; depth; cycle; deterministic pages; capability absent degrades. |
| Impact | Exact count; ordered `limit + 1 offset`; 503 records across non-overlapping pages; missing/incompatible index degrades. |
| Projection | Wrong item family, invalid counts, false exactness, token/truncation disagreement, and malformed subject fail closed. |
| Contracts | Catalog, schemas, samples, API docs, OpenAPI, packaged content, and public command docs regenerated and checked. |
| End to end | Resolve identity, prove references/callers, continue a page, estimate impact; no text search, raw dispatch, or unbounded request. |

### Task 1: Public command and bounded argument contract

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent.rs`
- Create: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`
- Modify: `cli-rs/tests/cli_core_smoke.rs`

**Interfaces:**

- Consumes: ADR 0016 exact selector fields and ADR 0020 result-view arguments.
- Produces: `AgentReferencesArgs`, `AgentCallersArgs`, `AgentCalleesArgs`,
  `AgentImplementationsArgs`, `AgentHierarchyArgs`, `AgentRelationLimit`,
  `AgentRelationDepth`, `AgentHierarchyDirection`, and
  `AgentExactSymbolSelectorArgs` for later execution tasks.

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
        ["agent", "references", "--symbol", "sample.Service"].as_slice(),
        ["agent", "callers", "--symbol", "sample.Service.run"].as_slice(),
        ["agent", "callees", "--symbol", "sample.Service.run"].as_slice(),
        ["agent", "implementations", "--symbol", "sample.Service"].as_slice(),
        [
            "agent",
            "hierarchy",
            "--symbol",
            "sample.Service",
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

Add a table test for limits `0`, `201`, depths `0`, `9`, unknown hierarchy
direction, empty symbol, and a malformed page token. Assert exit code 2 and
that no runtime descriptor or source-index fixture is opened.

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
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub file_hint: Option<String>,
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

Use Clap range parsers for `--limit 1..=200` and `--depth 1..=8`. Remove
`references`, `callers`, and `caller_depth` from `AgentSymbolArgs`; leave its
`--limit` responsible only for discovery candidate cardinality.

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
git add cli-rs/src/cli/agent.rs cli-rs/src/agent.rs cli-rs/tests/agent_relationship_navigation_smoke.rs cli-rs/tests/agent_command_surface_smoke.rs cli-rs/tests/cli_core_smoke.rs
git commit -m "feat: define typed relationship commands"
```

### Task 2: Shared reference occurrence and page evidence

**Files:**

- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/SymbolIdentity.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolEvidence.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ContainingSymbolUnavailableReason.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferenceOccurrence.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationCountKind.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationPageInfo.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationPageToken.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/RelationPageOffset.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/ReferencesQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/ReferencesResult.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedReferencesQuery.kt`
- Delete: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ReferencePageOffset.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/RelationshipModelTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- Modify: `analysis-api/AGENTS.md`

**Interfaces:**

- Consumes: #337's `PositiveInt`, `ReferencesQuery.maxResults`, canonical
  reference offset, `ReferencesResult.totalCount`, and `PageInfo` behavior.
- Produces: `SymbolIdentity`, closed `ContainingSymbolEvidence`,
  `ReferenceOccurrence`, `RelationPageInfo`, and reusable
  `RelationPageOffset` for all Kotlin relationship endpoints.

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

Test `RelationPageOffset.parse("0")`, `parse("10000")`, and rejection of
`-1`, `10001`, whitespace, signs, and non-decimal input. Test that
`RelationPageInfo` rejects returned count greater than known count, a next
offset without truncation, truncation without a next offset, and an exact
count smaller than `offset + returnedCount`.

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
    val location: Location,
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
    val offset: RelationPageOffset,
    val knownCount: Int,
    val countKind: RelationCountKind,
    val returnedCount: Int,
    val truncated: Boolean,
    val nextPageToken: RelationPageToken?,
) {
    companion object {
        fun create(
            offset: RelationPageOffset,
            knownCount: Int,
            countKind: RelationCountKind,
            returnedCount: Int,
            hasMore: Boolean,
        ): RelationPageInfo
    }
}

@JvmInline
value class RelationPageOffset private constructor(val value: Int) {
    companion object {
        const val MAX_VALUE: Int = 10_000
        fun parse(raw: String): RelationPageOffset
        fun from(value: Int): RelationPageOffset
    }
}

@JvmInline
value class RelationPageToken private constructor(val value: String) {
    fun offset(): RelationPageOffset

    companion object {
        fun from(offset: RelationPageOffset): RelationPageToken
        fun parse(raw: String): RelationPageToken
    }
}
```

Change `ReferencesResult.references` to `List<ReferenceOccurrence>`. Replace
#337's reference-specific offset with `RelationPageOffset` without weakening
its positive request limit or two-page no-overlap contract. Record this shared
relationship contract in `analysis-api/AGENTS.md`.

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

- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`

**Interfaces:**

- Consumes: `ReferenceOccurrence`, `ContainingSymbolEvidence`,
  `RelationPageOffset`, and #337's positive reference limit.
- Produces: exact `totalCount`, deterministic `limit + 1` pages, and semantic
  containing-symbol proof for the existing `symbol/references` endpoint.

- [ ] **Step 1: Add RED backend and server scenarios**

Build one Kotlin fixture containing a member call, a top-level call, and a
reference whose nearest PSI owner cannot be converted to a supported symbol.
Assert the three containing-symbol outcomes. Add 205 ordered usages and assert:

```kotlin
assertEquals(first.references.map { it.location }.toSet().size, first.references.size)
assertTrue(first.references.none { it in second.references })
assertEquals((0 until 4).toList(), first.references.map { it.location.startLine })
assertEquals((4 until 8).toList(), second.references.map { it.location.startLine })
assertEquals(205, first.totalCount)
assertEquals("4", first.page?.nextPageToken)
```

At the dispatcher boundary, assert `maxResults=4` and `pageToken="4"` become
typed query values and that malformed tokens return the relationship usage
error without calling the fake backend.

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```console
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.KastPluginBackendContractTest --no-daemon
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherTest --no-daemon
```

Expected: FAIL because references still expose bare locations and no
containing-symbol evidence.

- [ ] **Step 3: Collect proof while PSI is owned and preserve bounded work**

Map each `ReferencesSearch` usage to a `ReferenceOccurrence` in the IDEA
read-action. Convert the nearest supported containing declaration to
`SymbolIdentity`; emit `TopLevel` only after proving no containing declaration,
and `Unavailable` when a declaration exists but semantic conversion fails.
Sort occurrences by normalized file path, start offset, end offset, and known
containing FQ name. Count cardinality separately, then slice only
`offset..offset + limit + 1` and drop the proof record.

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
git add analysis-server backend-idea
git commit -m "feat: report bounded reference ownership"
```

### Task 4: Identity-first call, implementation, and hierarchy endpoints

**Files:**

- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastCallersRequest.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastCallersQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastCallersResponse.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastExactSymbolSelector.kt`
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
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/CallHierarchyEngine.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/hierarchy/TypeHierarchyEngine.kt`
- Create: `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/hierarchy/RelationshipPagingTest.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`

**Interfaces:**

- Consumes: exact resolver outcomes, `RelationPageOffset`, positive limits,
  `SymbolIdentity`, backend capability enums, and full-fidelity raw hierarchy
  results.
- Produces: internal `symbol/implementations` and `symbol/hierarchy` methods;
  paged flat call/implementation/hierarchy records; typed available,
  subject-not-found, subject-ambiguous, degraded, and failure variants.

- [ ] **Step 1: Add RED model and traversal tests**

Create balanced and cyclic call/type graphs whose insertion order differs from
canonical order. Assert breadth-first call pages and identity-sorted
implementation/type pages. The page window assertion is:

```kotlin
val first = engine.query(offset = RelationPageOffset.from(0), limit = PositiveInt.from(4))
val second = engine.query(offset = RelationPageOffset.from(4), limit = PositiveInt.from(4))
assertEquals(4, first.records.size)
assertEquals(4, second.records.size)
assertTrue(first.records.toSet().intersect(second.records.toSet()).isEmpty())
assertEquals(RelationCountKind.LOWER_BOUND, first.page.countKind)
assertEquals(RelationCountKind.EXACT, second.page.countKind)
```

Add dispatcher scenarios for each endpoint covering resolved, not found,
ambiguous, missing capability, and malformed backend payload. Assert that a
missing capability returns the exact ADR 0022 degraded code and no raw backend
query is issued.

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
Use the same closed outcome vocabulary for all three families:

```kotlin
sealed interface KastHierarchyResponse {
    data class Available(
        val subject: SymbolIdentity,
        val records: List<TypeHierarchyRelation>,
        val page: RelationPageInfo,
    ) : KastHierarchyResponse

    data class SubjectNotFound(val selector: KastExactSymbolSelector) : KastHierarchyResponse
    data class SubjectAmbiguous(val candidates: List<SymbolIdentity>) : KastHierarchyResponse
    data class Degraded(
        val code: RelationDegradedCode,
        val capability: ReadCapability,
    ) : KastHierarchyResponse
    data class Failure(val code: String, val message: String) : KastHierarchyResponse
}
```

Use family-specific record types so call sites, implementation declarations,
and hierarchy depth are required by construction rather than nullable fields.

- [ ] **Step 4: Implement deterministic bounded traversal and dispatch**

Sort every child/candidate collection before traversal. Calls flatten
breadth-first using `(depth, parent identity, related identity, call-site)`;
implementations and hierarchy sort by `(fqName, kind, file, offset)`. Request
no more than `offset + limit + 1`, retain cycle/timeout/max-depth evidence, and
return exact counts only when exhausted. Map `CALL_HIERARCHY`,
`IMPLEMENTATIONS`, and `TYPE_HIERARCHY` absence to their typed degraded codes.

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
- Modify: `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`

**Interfaces:**

- Consumes: Task 1 arguments, Kotlin typed endpoint responses, ADR 0020 view
  modes, and #337 relationship projection helpers.
- Produces: `AgentRelationPageToken`, family request runners, closed result
  records, typed degraded projections, and query-composition proof.

- [ ] **Step 1: Add RED end-to-end scripted-backend tests**

Script exact identities and each internal method. Resolve `sample.Service`
once through `agent symbol`, copy `result.identity.fqName`, and invoke all five
commands. Assert the backend receives canonical `symbol`, hard selector fields,
limit `4`, offset `0`, fixed call direction, and depth. Assert no request uses
`symbol/query`, lexical mode, or a public raw method.

Add a first references page with internal next offset 4, capture the public
token, invoke page two, and assert no overlapping records. Reuse that token
with a different symbol, workspace, relation, and depth; each must return
`RELATION_PAGE_TOKEN_MISMATCH` before the scripted backend receives a request.

- [ ] **Step 2: Run the Rust relation test and confirm RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke
```

Expected: FAIL because execution and relation projection are not wired.

- [ ] **Step 3: Implement the exact public token format**

Serialize tokens as four dot-separated fields:

```text
krp1.<relation-kind>.<24-lowercase-hex-sha256-prefix>.<decimal-offset>
```

The SHA-256 input is newline-delimited canonical workspace root, relation
kind, FQ symbol, optional canonical kind/file/containing type,
include-declaration choice, fixed direction, depth, and page limit. Parsing
must reject unknown versions/kinds, non-lowercase hex, offsets above 10,000,
and a fingerprint mismatch. Keep both decoded offset and query proof private:

```rust
struct AgentRelationPageToken {
    relation: AgentRelationKind,
    query_fingerprint: [u8; 12],
    offset: AgentRelationPageOffset,
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

Project `AVAILABLE`, `SUBJECT_NOT_FOUND`, `SUBJECT_AMBIGUOUS`, and `DEGRADED`
as expected typed results. Reject wrong-family records, returned-count
mismatches, false exact counts, truncation without a next offset, or a next
offset without truncation as `INVALID_RELATION_RESPONSE`. Move reusable #337
location/identity helpers out of symbol-only code without duplicating their
wire validation.

- [ ] **Step 5: Run focused Rust tests GREEN**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke --test agent_command_surface_smoke
```

Expected: all tests PASS, and detailed view requests retain the same limit and
offset as compact requests.

- [ ] **Step 6: Commit public relationship execution**

```console
git add cli-rs/src/agent.rs cli-rs/src/agent cli-rs/tests/agent_relationship_navigation_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs cli-rs/tests/agent_command_surface_smoke.rs
git commit -m "feat: navigate relationships by exact identity"
```

### Task 6: Paged source-impact composition

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
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
- Produces: impact `--page-token`, internal typed offset, stable ordering, and
  exact page evidence.

- [ ] **Step 1: Add RED 503-node database and CLI paging tests**

Seed 503 impact nodes in reverse insertion order. Query offsets 0 and 4 with
limit 4. Assert both report total 503, return four unique nodes, and sort by
`(depth, sourcePath, viaTargetFqName, edgeKind)`. At the CLI boundary, capture
page one's token and assert page two has no overlap. Assert a references token
and a token for another symbol fail before SQLite opens.

- [ ] **Step 2: Run impact tests and confirm RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked metrics_database
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_relationship_navigation_smoke impact
```

Expected: FAIL because impact has no page-token argument or offset query.

- [ ] **Step 3: Add typed offset to the direct metrics request**

Extend only impact requests with `RelationPageOffset`; other metrics remain
unchanged. Preserve the independent count query. The row query must end with:

```sql
ORDER BY depth ASC,
         source_path ASC,
         via_target_fq_name ASC,
         edge_kind ASC
LIMIT ?1 OFFSET ?2
```

Bind `limit + 1` and the validated offset. Drop only the extra continuation
row. Return exact total count, returned count, truncation, and internal next
offset; the projection wraps that offset in the query-bound public token.

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
identity first, demonstrates one page continuation, and forbids `rg`, `grep`,
`find`, `agent call`, and raw position methods in the positive workflow. Add a
catalog test requiring `symbol/implementations` and `symbol/hierarchy` request
schemas and every typed response variant.

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
Update references/callers fields and response variants. Teach this exact
public sequence in docs and the installed skill:

```console
kast agent symbol --query OrderService --fields identity,location --workspace-root "$PWD"
kast agent references --symbol com.example.OrderService --workspace-root "$PWD"
kast agent callers --symbol com.example.OrderService.submit --depth 2 --workspace-root "$PWD"
kast agent impact --symbol com.example.OrderService --depth 2 --workspace-root "$PWD"
```

Explain typed degraded outcomes and page-token reuse without exposing token
encoding, raw RPC method names, or backend implementation classes.

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
top-level production type. Confirm the diff does not touch #338
`workspace_inventory` files or introduce Gradle task/plugin relation claims.
Confirm all SQL row queries are bounded and all detailed relation requests
carry the explicit limit and offset.

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
terminal results, output-budget maxima, and the known unprepared-worktree Kast
limitation. Ask a fresh reviewer to check exact identity, token binding,
count/truncation truthfulness, capability degradation, SQL/backend bounds, and
#338/#340 isolation. Repair findings with new focused RED/GREEN commits and
rerun affected plus full gates.

- [ ] **Step 6: Leave the branch clean**

```console
git status --short --branch
git log --oneline --decorate origin/main..HEAD
```

Expected: only the intentionally ignored `.agent-turn` report is outside Git;
the tracked worktree is clean and every issue-owned commit is visible.
