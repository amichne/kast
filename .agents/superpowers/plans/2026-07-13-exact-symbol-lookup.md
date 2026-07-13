# Exact Symbol Lookup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the public symbol command return a unique exact identity or a typed not-found/ambiguous outcome, with fuzzy candidates available only through explicit discovery mode.

**Architecture:** Harden the existing compiler `symbol/resolve` RPC into a hard-constrained exact boundary, then map its sealed outcomes into a typed Rust public result. The CLI falls back to indexed exact lookup only for an explicit availability-code allowlist; discovery is a separate lexical request and relation requests consume only canonical compiler identity.

**Tech Stack:** Kotlin 2.2, kotlinx.serialization, JUnit Jupiter, Rust 2024, Clap, serde, SQLite source index, Gradle, Cargo, Zensical.

## Global Constraints

- Public default mode is `exact`; fuzzy discovery requires `--mode discovery`.
- Exact `NOT_FOUND` and `AMBIGUOUS` never invoke lexical discovery or indexed fallback.
- Backticks are normalized only for identity comparison; results retain canonical identity.
- Kind, file hint, and containing type are hard constraints.
- Indexed fallback remains exact and runs only for typed compiler/backend unavailability.
- Relation steps run only after compiler `RESOLVE_SUCCESS`, using its canonical fully qualified name.
- Production Kotlin keeps one non-private top-level named type per same-named file; direct sealed variants stay with their root.
- Authored catalog sources generate protocol artifacts; generated files are never hand-edited.
- This session executes inline because the task explicitly forbids subagents.

---

### Task 1: Compiler Exact Outcome Contract

**Files:**

- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastResolveResponse.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/contract/skill/KastResolveResponseTest.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`

**Interfaces:**

- Consumes: `KastResolveRequest`, `KastResolveQuery`, `Symbol`, and compiler workspace-symbol/resolve APIs.
- Produces: `KastResolveResponse` with `RESOLVE_SUCCESS`, `RESOLVE_NOT_FOUND`, `RESOLVE_AMBIGUOUS`, and legacy `RESOLVE_FAILURE` variants. `KastResolveResponse.Source.COMPILER` serializes as `compiler`.

- [ ] **Step 1: Add response migration tests before moving production types**

Create `KastResolveResponseTest.kt` with serializer tests that decode existing success and failure payloads and the new expected variants:

```kotlin
class KastResolveResponseTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `existing success and failure discriminators remain compatible`() {
        val success = json.decodeFromString<KastResolveResponse>(successJson)
        val failure = json.decodeFromString<KastResolveResponse>(failureJson)

        assertInstanceOf(KastResolveSuccessResponse::class.java, success)
        assertInstanceOf(KastResolveFailureResponse::class.java, failure)
    }

    @Test
    fun `expected exact outcomes decode distinctly`() {
        val notFound = json.decodeFromString<KastResolveResponse>(notFoundJson)
        val ambiguous = json.decodeFromString<KastResolveResponse>(ambiguousJson)

        assertInstanceOf(KastResolveNotFoundResponse::class.java, notFound)
        assertInstanceOf(KastResolveAmbiguousResponse::class.java, ambiguous)
    }
}
```

- [ ] **Step 2: Run the API test and verify RED**

Run:

```console
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.contract.skill.KastResolveResponseTest
```

Expected: compilation fails because the not-found and ambiguous response types do not exist.

- [ ] **Step 3: Move the sealed response owner and add typed variants**

Remove only the `KastResolveResponse` root and its existing direct variants from `SkillContracts.kt`. Create `KastResolveResponse.kt` with this shape:

```kotlin
@Serializable
sealed interface KastResolveResponse {
    @Serializable
    enum class Source {
        @SerialName("compiler")
        COMPILER,
    }
}

@Serializable
@SerialName("RESOLVE_SUCCESS")
data class KastResolveSuccessResponse(
    val ok: Boolean = true,
    val source: KastResolveResponse.Source = KastResolveResponse.Source.COMPILER,
    val query: KastResolveQuery,
    val symbol: Symbol,
    val filePath: String,
    val offset: Int,
    val candidate: KastCandidate,
    val context: KastResolveContext? = null,
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_NOT_FOUND")
data class KastResolveNotFoundResponse(
    val ok: Boolean = true,
    val source: KastResolveResponse.Source = KastResolveResponse.Source.COMPILER,
    val query: KastResolveQuery,
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_AMBIGUOUS")
data class KastResolveAmbiguousResponse(
    val ok: Boolean = true,
    val source: KastResolveResponse.Source = KastResolveResponse.Source.COMPILER,
    val query: KastResolveQuery,
    val candidates: List<Symbol>,
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_FAILURE")
data class KastResolveFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastResolveQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastResolveResponse
```

- [ ] **Step 4: Run the API test and verify GREEN**

Run the focused test from Step 2. Expected: pass.

- [ ] **Step 5: Add server RED tests for exact selection**

Extend `AnalysisDispatcherTest` with a private backend delegating to the sample backend while overriding `workspaceSymbolSearch` and `resolveSymbol`. Add separate tests proving:

```kotlin
@Test fun `symbol resolve returns not found instead of a fuzzy candidate`()
@Test fun `symbol resolve returns ambiguous for overloaded exact members`()
@Test fun `symbol resolve matches backticked simple and qualified names exactly`()
@Test fun `symbol resolve applies kind file and containing type as hard constraints`()
```

The fuzzy fixture must include `sample.LegacyOrderService` for query `MissingOrderService`; the overload fixture must include two `sample.Parser.parse` symbols at different offsets; the backtick fixture must return canonical `sample.when`; and each hard-constraint mismatch must produce `KastResolveNotFoundResponse`.

- [ ] **Step 6: Run the server tests and verify RED**

Run:

```console
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherTest
```

Expected: current resolver either chooses the fuzzy first candidate or returns success where ambiguity/not-found is required.

- [ ] **Step 7: Implement exact compiler candidate selection**

In `SkillRpcOrchestrator`, separate broad candidate collection from discovery ranking. Add pure helpers with these contracts:

```kotlin
private fun exactIdentityMatches(requested: String, candidateFqName: String): Boolean
private fun normalizedKotlinIdentity(value: String): String
private fun exactFileHintMatches(fileHint: String, candidateFile: String): Boolean
private fun exactContainingTypeMatches(containingType: String, candidate: Symbol): Boolean
private suspend fun exactNamedSymbolCandidates(
    symbolName: String,
    fileHint: String?,
    kind: WrapperNamedSymbolKind?,
    containingType: String?,
): List<RankedNamedSymbolCandidate>
```

`resolve()` returns not-found for zero candidates, ambiguous for more than one, and resolves exactly one candidate. Discovery continues through `rankedNamedSymbolCandidates`; no lexical behavior is removed from `symbol/discover`.

- [ ] **Step 8: Run focused Kotlin tests and verify GREEN**

Run:

```console
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.contract.skill.KastResolveResponseTest
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherTest
```

Expected: pass.

- [ ] **Step 9: Commit the compiler contract slice**

```console
git add analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt \
  analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastResolveResponse.kt \
  analysis-api/src/test/kotlin/io/github/amichne/kast/api/contract/skill/KastResolveResponseTest.kt \
  analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt \
  analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt
git commit -m "fix: make compiler symbol lookup exact"
```

### Task 2: Typed Public Exact And Discovery Modes

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent.rs`
- Create: `cli-rs/src/agent/symbol_lookup.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`
- Modify: `cli-rs/tests/support/mod.rs`

**Interfaces:**

- Consumes: compiler `KastResolveResponse` JSON and indexed `SYMBOL_QUERY_SUCCESS` JSON.
- Produces: `AgentSymbolMode::{Exact, Discovery}` and a structured `KAST_AGENT_SYMBOL_LOOKUP` result with a closed `outcome` and explicit `source`.

- [ ] **Step 1: Add public CLI RED tests**

Add deterministic fake-UDS backend support and tests asserting:

```rust
#[test] fn agent_symbol_defaults_to_exact_and_returns_compiler_identity()
#[test] fn agent_symbol_not_found_does_not_request_indexed_or_lexical_fallback()
#[test] fn agent_symbol_ambiguous_does_not_request_indexed_or_lexical_fallback()
#[test] fn agent_symbol_discovery_requests_lexical_mode_explicitly()
#[test] fn agent_symbol_relations_use_canonical_compiler_identity()
#[test] fn agent_symbol_discovery_rejects_relation_flags_before_io()
```

The fake backend records methods and params. Not-found and ambiguous cases must record only `symbol/resolve`; the relation case must record `symbol/resolve` followed by relation requests whose `symbol` is the returned canonical `sample.when` rather than the backticked input.

- [ ] **Step 2: Run the command-surface test and verify RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke
```

Expected: Clap rejects `--mode`, and the default command still sends indexed exact+lexical query before resolve.

- [ ] **Step 3: Add typed CLI mode and outcome types**

Add:

```rust
#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Default)]
pub enum AgentSymbolMode {
    #[default]
    Exact,
    Discovery,
}
```

`AgentSymbolArgs.mode` uses `#[arg(long, value_enum, default_value_t)]`. In `agent/types.rs`, define serializable `AgentSymbolLookupResult`, `AgentSymbolLookupOutcome`, `AgentSymbolLookupSource`, `AgentSymbolRelation`, and `AgentCompilerFallback` types. Use serde tags so every state carries only valid fields.

- [ ] **Step 4: Pin and test the exact availability allowlist**

In `agent/symbol_lookup.rs`, define:

```rust
const INDEXED_EXACT_FALLBACK_CODES: [&str; 13] = [
    "MACOS_PLUGIN_WORKSPACE_REQUIRED",
    "NO_BACKEND_AVAILABLE",
    "IDEA_NOT_RUNNING",
    "IDEA_BACKEND_DISABLED",
    "IDEA_PLUGIN_NOT_INSTALLED",
    "IDEA_LAUNCH_FAILED",
    "DAEMON_START_ERROR",
    "DAEMON_UNREACHABLE",
    "RUNTIME_TIMEOUT",
    "RPC_RESPONSE_TIMEOUT",
    "RPC_RESPONSE_MISSING",
    "CAPABILITY_NOT_SUPPORTED",
    "CAPABILITIES_UNAVAILABLE",
];
```

Add a table test that iterates every constant and asserts fallback is allowed. Add negative cases for `IDEA_LAUNCH_CONFIG_INVALID`, `RPC_RESPONSE_INVALID`, `RESOLVE_FAILURE`, `VALIDATION_ERROR`, and `AGENT_REQUEST_INVALID`; each must remain fail-closed.

- [ ] **Step 5: Implement compiler-first exact orchestration**

Replace generic symbol steps with `execute_agent_symbol_exact` and `execute_agent_symbol_discovery`. Exact execution:

```rust
match compiler_result_type {
    "RESOLVE_SUCCESS" => resolved_from_compiler(...),
    "RESOLVE_NOT_FOUND" => not_found_from_compiler(...),
    "RESOLVE_AMBIGUOUS" => ambiguous_from_compiler(...),
    "RESOLVE_FAILURE" => preserve_compiler_failure(...),
    other => invalid_compiler_contract(other),
}
```

Only an allowlisted `AgentError.code` reaches `indexed_exact_fallback`. If `containing_type` is present, return the compiler availability error because the current source index cannot prove that constraint. Build file-hint and kind filters into the exact index request, use `modes=["exact"]`, and never include lexical mode. Discovery uses `modes=["lexical"]` and returns `source=fuzzy`.

- [ ] **Step 6: Run Rust unit and public tests and verify GREEN**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked agent::
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke
```

Expected: pass, including all 13 allowlisted codes and all negative codes.

### Task 3: Indexed Exact Identity And Backticks

**Files:**

- Modify: `cli-rs/src/symbol_query/ranking_and_filters.rs`
- Modify: `cli-rs/tests/support/metrics.rs`
- Modify: `cli-rs/tests/symbol_query_smoke.rs`

**Interfaces:**

- Consumes: raw query text and indexed declaration `fq_name`/derived simple name.
- Produces: exact signals only when normalized Kotlin identity is equal; returned declarations remain unchanged.

- [ ] **Step 1: Add source-index RED tests**

Seed canonical indexed declarations `sample.when`, `alpha.Parser`, and `beta.Parser`, plus lexical-only `sample.MissingOrderServiceLegacy`. Add tests:

```rust
#[test] fn indexed_exact_lookup_normalizes_backticks_without_rewriting_identity()
#[test] fn indexed_exact_lookup_returns_all_ambiguous_simple_name_matches()
#[test] fn indexed_exact_lookup_never_returns_lexical_only_candidates()
```

- [ ] **Step 2: Run the symbol-query test and verify RED**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test symbol_query_smoke
```

Expected: the backticked exact query returns no results before normalization.

- [ ] **Step 3: Implement comparison-only backtick normalization**

Add a pure `normalized_kotlin_identity` helper and use it only inside
`exact_matches`. Preserve case sensitivity and the original `fq_name` and
`simple_name` fields in response records. Do not change lexical tokenization.

- [ ] **Step 4: Run symbol-query and agent tests and verify GREEN**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test symbol_query_smoke --test agent_command_surface_smoke
```

Expected: pass.

- [ ] **Step 5: Commit the Rust behavior slices**

```console
git add cli-rs/src/cli/agent.rs cli-rs/src/agent.rs cli-rs/src/agent \
  cli-rs/src/symbol_query/ranking_and_filters.rs cli-rs/tests/agent_command_surface_smoke.rs \
  cli-rs/tests/symbol_query_smoke.rs cli-rs/tests/support/mod.rs cli-rs/tests/support/metrics.rs
git commit -m "feat: add fail-closed exact symbol mode"
```

### Task 4: Authored Guidance, Catalog, And Generated Contracts

**Files:**

- Modify: `cli-rs/resources/kast-skill/references/commands.json`
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/workflows.md`
- Modify: `docs/reference/agent-commands.md`
- Modify: `docs/use/inspect-kotlin.md`
- Modify: `docs/learn/evidence-model.md`
- Modify generated outputs reported by `kast developer release generate contract`
- Modify: `cli-rs/tests/rpc_catalog_smoke.rs`
- Modify: `cli-rs/tests/packaged_content_smoke.rs`

**Interfaces:**

- Consumes: accepted ADR 0016 and the implemented CLI/backend types.
- Produces: catalog variants, migration guidance, packaged skill routing, public reference/how-to/explanation pages, and regenerated protocol artifacts.

- [ ] **Step 1: Add catalog and packaged-guidance RED assertions**

Assert that `symbol/resolve` lists all four response variants and that packaged guidance teaches exact default plus explicit `--mode discovery`. Keep raw catalog dispatch internal.

- [ ] **Step 2: Run contract tests and verify RED**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test rpc_catalog_smoke --test packaged_content_smoke
```

Expected: current catalog knows only success/failure and packaged guidance omits discovery mode.

- [ ] **Step 3: Update authored sources**

Update `commands.json` first. Preserve `RESOLVE_SUCCESS` and `RESOLVE_FAILURE` while adding `RESOLVE_NOT_FOUND` and `RESOLVE_AMBIGUOUS`. Document that existing internal consumers must match the new variants.

Apply Diataxis boundaries:

- `docs/reference/agent-commands.md`: factual mode, outcome, source, and flag reference.
- `docs/use/inspect-kotlin.md`: how to use exact lookup and opt into discovery.
- `docs/learn/evidence-model.md`: why source and typed ambiguity matter.
- Packaged `SKILL.md` and workflows: concise routing examples only.

- [ ] **Step 4: Regenerate owned protocol outputs**

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract
```

Review every generated path and keep only expected catalog-derived changes.

- [ ] **Step 5: Verify generated and docs contracts**

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test rpc_catalog_smoke --test packaged_content_smoke
.github/scripts/test-docs-content-contract.sh
zensical build --clean
```

Expected: all pass.

- [ ] **Step 6: Commit source and generated contract alignment**

```console
git add cli-rs/resources/kast-skill docs cli-rs/protocol cli-rs/tests/rpc_catalog_smoke.rs cli-rs/tests/packaged_content_smoke.rs
git commit -m "docs: teach exact and discovery symbol modes"
```

### Task 5: Widening Verification, Review, And Durable Report

**Files:**

- Create ignored report: `.agent-turn/issue-334-report.md`
- Create ignored Kotlin scorecard/evidence under `.agent-turn/kotlin-agentic-correctness/`

**Interfaces:**

- Consumes: the approved design, implementation diff, and validation logs.
- Produces: clean commits, no untracked scoped source, a no-`Fail` Kotlin scorecard, and a durable worktree report.

- [ ] **Step 1: Run Kotlin diagnostics or record the prepared-workspace limitation**

Run `kast agent verify --workspace-root "$PWD"`. If plugin metadata remains absent, record `MACOS_PLUGIN_WORKSPACE_REQUIRED` and do not run `kast setup`. Use Gradle compile/test output as the authoritative Kotlin proof.

- [ ] **Step 2: Run full formatting, lint, and test gates**

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
./gradlew :analysis-api:test :analysis-server:test
.github/scripts/test-docs-content-contract.sh
zensical build --clean
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 3: Review requirements and diff**

Check every issue criterion against a test, inspect `git diff --stat`, review targeted diffs, confirm generated ownership, and verify no fuzzy request appears in exact execution. Confirm the availability test enumerates all 13 allowlisted codes and rejects the five negative codes.

- [ ] **Step 4: Write Kotlin scorecard and durable report**

Write the nine-dimension Kotlin scorecard with `Pass`, `Concern`, or `Fail`; no `Fail` may remain. Write `.agent-turn/issue-334-report.md` with status, commits, tests, evidence paths, and concerns, including the temporary-worktree Kast limitation.

- [ ] **Step 5: Commit any final scoped corrections and report the branch state**

Stage only tracked issue files. Run `git diff --cached --check`, commit with a conventional focused subject if needed, and leave the branch/worktree local without push or PR.
