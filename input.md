This plan delivers 5 implementation initiatives and creates 2 GitHub issues for deferred work.

---

## PART A: Create GitHub Issues for deferred work

Use `gh issue create` to create two issues in the `amichne/kast` repository:

### Issue 1: CI remote index snapshots

Title: `feat: CI-generated remote index snapshots for zero cold-start indexing`

Body:
```
## Summary
Add a CI workflow that generates and publishes `source-index.db` snapshots so new sessions can hydrate from a pre-built index instead of rebuilding from scratch.

## Context
- `SourceIndexHydrator` in `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/SourceIndexHydrator.kt` already supports downloading and atomically placing a `source-index.db` from `file://`, `http://`, or `https://` URLs.
- The standalone backend's `KastConfig` already has `indexing.remote` config support (`enabled` + `sourceIndexUrl`).
- First-start indexing on big multi-module projects takes 30-60 seconds (see `docs/troubleshooting.md`).

## Deliverables
1. New CI workflow (`.github/workflows/index-snapshot.yml` or job in existing workflow) that:
   - Checks out the repo, sets up JDK 21 + Gradle
   - Runs `kast workspace ensure --accept-indexing=true` and waits for `state: READY`
   - Locates `source-index.db` under the kast cache directory
   - Uploads it as a workflow artifact or release asset
2. Verify standalone backend hydration works end-to-end (port `SourceIndexHydrator` logic if needed)
3. Document the workflow in `docs/getting-started/backends.md`
```

Labels: `enhancement`, `performance`

### Issue 2: Persistent JSON-RPC connection from Copilot extension

Title: `feat: persistent JSON-RPC socket connection from Copilot extension`

Body:
```
## Summary
Replace per-tool-call process spawning in the Copilot extension with a persistent Unix domain socket connection to the kast daemon.

## Context
Currently, every `kast_*` tool call in `.github/extensions/kast/extension.mjs` spawns a new process via `execFile("bash", ["-lc", command])` (line 56-72). This means: Node.js → bash → kast CLI → JSON-RPC to daemon → response → stdout parse. For rapid interactive use, this per-call overhead adds up.

The daemon already speaks JSON-RPC over Unix domain sockets. The extension could open a persistent `net.Socket` connection at session start and send requests directly.

## Deliverables
1. In `onSessionStart`, after resolving the kast binary:
   - Run `kast workspace status` to get the daemon's socket path from the descriptor file
   - Open a persistent `net.Socket` (Node.js `net` module) connection
   - Store the socket as a module-level variable
2. Create `callKastDirect(method, params)` that sends JSON-RPC over the socket
3. Fall back to `callKastSkill` (process spawn) if socket is unavailable
4. Handle reconnection on daemon restart
```

Labels: `enhancement`, `performance`

---

## PART B: Phase 1a — Add `kast_workspace_symbol` to the Copilot extension

The `workspace-symbol` API is fully implemented in both backends and the CLI already supports `kast workspace-symbol --pattern=X`. What's missing is the skill wrapper path (the `kast workspace-symbol '<json>'` direct invocation used by the extension).

### Step 1: Add enum entry

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperName.kt`

Add `WORKSPACE_SYMBOL("workspace-symbol", "kast_workspace_symbol")` to the enum, after `METRICS`. The `when` expressions in `SkillWrapperExecutor` and `SkillWrapperSerializer` are exhaustive, so the compiler will guide you to add the missing branches.

### Step 2: Add wrapper contract types

File: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/wrapper/WrapperContracts.kt`

Add these types following the existing pattern (e.g., `KastReferencesRequest`/`KastReferencesSuccessResponse`):

```kotlin
@Serializable
data class KastWorkspaceSymbolRequest(
    val workspaceRoot: String? = null,
    val pattern: String,
    val kind: String? = null,
    val maxResults: Int = 100,
    val regex: Boolean = false,
    val includeDeclarationScope: Boolean = false,
)

@Serializable
data class KastWorkspaceSymbolQuery(
    val workspaceRoot: String,
    val pattern: String,
    val kind: String? = null,
    val maxResults: Int = 100,
    val regex: Boolean = false,
    val includeDeclarationScope: Boolean = false,
)

@Serializable
sealed interface KastWorkspaceSymbolResponse

@Serializable
@SerialName("WORKSPACE_SYMBOL_SUCCESS")
data class KastWorkspaceSymbolSuccessResponse(
    val ok: Boolean = true,
    val query: KastWorkspaceSymbolQuery,
    val symbols: List<Symbol>,
    val page: PageInfo? = null,
    val logFile: String,
) : KastWorkspaceSymbolResponse

@Serializable
@SerialName("WORKSPACE_SYMBOL_FAILURE")
data class KastWorkspaceSymbolFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastWorkspaceSymbolQuery,
    val logFile: String,
) : KastWorkspaceSymbolResponse
```

Make sure to import `Symbol` and `PageInfo` from the contract package.

### Step 3: Add executor handler

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperExecutor.kt`

Add `SkillWrapperName.WORKSPACE_SYMBOL -> executeWorkspaceSymbol(rawJson)` to the `when` block in `execute()` (around line 92-102).

Add the handler method following the `executeWorkspaceFiles` pattern (lines 107-129):

```kotlin
private suspend fun executeWorkspaceSymbol(rawJson: String): Any {
    val request = json.decodeFromString<KastWorkspaceSymbolRequest>(rawJson)
    val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
    val options = runtimeOptionsFor(workspaceRoot)
    val kind = request.kind?.let { raw ->
        SymbolKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
    val query = WorkspaceSymbolQuery(
        pattern = request.pattern,
        kind = kind,
        maxResults = request.maxResults,
        regex = request.regex,
        includeDeclarationScope = request.includeDeclarationScope,
    )
    val result = cliService.workspaceSymbolSearch(options, query)
    return KastWorkspaceSymbolSuccessResponse(
        ok = true,
        query = KastWorkspaceSymbolQuery(
            workspaceRoot = workspaceRoot,
            pattern = request.pattern,
            kind = request.kind,
            maxResults = request.maxResults,
            regex = request.regex,
            includeDeclarationScope = request.includeDeclarationScope,
        ),
        symbols = result.payload.symbols,
        page = result.payload.page,
        logFile = SkillLogFile.placeholder(),
    )
}
```

Add the necessary import for `WorkspaceSymbolQuery` and the new wrapper types.

### Step 4: Add serializer branch

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperSerializer.kt`

Add to the `when` block in `encode()`:
```kotlin
SkillWrapperName.WORKSPACE_SYMBOL ->
    json.encodeToString(KastWorkspaceSymbolResponse.serializer(), response as KastWorkspaceSymbolResponse)
```

Import `KastWorkspaceSymbolResponse`.

### Step 5: Add extension tool

File: `.github/extensions/kast/extension.mjs`

Add to the `tools` array (after `kast_workspace_files`):
```javascript
{
  name: "kast_workspace_symbol",
  description:
    "Search the workspace for Kotlin symbols by name pattern via kast workspace-symbol. Supports substring matching (default) and regex. Use to find declarations across the entire codebase — far more precise than grep/rg for symbol names because it understands Kotlin semantics (overloads, inherited members, cross-module references).",
  parameters: {
    type: "object",
    properties: {
      pattern: { type: "string", description: "Search pattern to match against symbol names." },
      kind: { type: "string", description: "Filter to symbols of this kind: CLASS, INTERFACE, OBJECT, FUNCTION, PROPERTY, ENUM_CLASS, ENUM_ENTRY, TYPE_ALIAS." },
      maxResults: { type: "integer", description: "Maximum number of symbols to return. Default 100." },
      regex: { type: "boolean", description: "When true, treats pattern as a regular expression." },
      includeDeclarationScope: { type: "boolean", description: "When true, includes the declaration body text for each symbol." },
      workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
    },
    required: ["pattern"],
  },
  handler: (args) => callKastSkill("workspace-symbol", args),
},
```

Update the `additionalContext` string (line 462) to include `kast_workspace_symbol` in the tool list.

### Step 6: Update AGENTS.md

File: `AGENTS.md`

Add row to the tool routing table (around line 90):
```
| Search symbols       | `kast_workspace_symbol`            | `kast workspace-symbol`                        |
```

Update the text search whitelist (line 98-99) to mention that `kast_workspace_symbol` should be preferred for symbol name searches over `grep`/`rg`.

### Step 7: Add tests

Add test cases to the existing test files:
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperContractTest.kt` — add WORKSPACE_SYMBOL contract test
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperRequestCasingTest.kt` — add WORKSPACE_SYMBOL casing test
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillCommandParsingTest.kt` — add parsing test for `kast workspace-symbol '{"pattern":"MyClass"}'`

Follow the exact patterns used by existing wrappers in each test file.

---

## PART C: Phase 1b — Add `kast_file_outline` to the Copilot extension

Identical pattern to Phase 1a but for the `file-outline` capability.

### Step 1: Add enum entry

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperName.kt`

Add `FILE_OUTLINE("file-outline", "kast_file_outline")` to the enum.

### Step 2: Add wrapper contract types

File: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/wrapper/WrapperContracts.kt`

```kotlin
@Serializable
data class KastFileOutlineRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
)

@Serializable
data class KastFileOutlineQuery(
    val workspaceRoot: String,
    val filePath: String,
)

@Serializable
sealed interface KastFileOutlineResponse

@Serializable
@SerialName("FILE_OUTLINE_SUCCESS")
data class KastFileOutlineSuccessResponse(
    val ok: Boolean = true,
    val query: KastFileOutlineQuery,
    val symbols: List<OutlineSymbol>,
    val logFile: String,
) : KastFileOutlineResponse

@Serializable
@SerialName("FILE_OUTLINE_FAILURE")
data class KastFileOutlineFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastFileOutlineQuery,
    val logFile: String,
) : KastFileOutlineResponse
```

### Step 3: Add executor handler

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperExecutor.kt`

Add `SkillWrapperName.FILE_OUTLINE -> executeFileOutline(rawJson)` to the `when` block.

```kotlin
private suspend fun executeFileOutline(rawJson: String): Any {
    val request = json.decodeFromString<KastFileOutlineRequest>(rawJson)
    val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
    val options = runtimeOptionsFor(workspaceRoot)
    val filePath = Path.of(request.filePath).toAbsolutePath().normalize().toString()
    val query = FileOutlineQuery(filePath = filePath)
    val result = cliService.fileOutline(options, query)
    return KastFileOutlineSuccessResponse(
        ok = true,
        query = KastFileOutlineQuery(
            workspaceRoot = workspaceRoot,
            filePath = filePath,
        ),
        symbols = result.payload.symbols,
        logFile = SkillLogFile.placeholder(),
    )
}
```

### Step 4: Add serializer branch

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperSerializer.kt`

```kotlin
SkillWrapperName.FILE_OUTLINE ->
    json.encodeToString(KastFileOutlineResponse.serializer(), response as KastFileOutlineResponse)
```

### Step 5: Add extension tool

File: `.github/extensions/kast/extension.mjs`

```javascript
{
  name: "kast_file_outline",
  description:
    "Get a hierarchical symbol outline for a Kotlin file via kast file-outline. Returns nested declarations (classes, functions, properties) with their signatures and locations. Lighter than scaffold — use when you only need the structural overview without references, type hierarchy, or file content.",
  parameters: {
    type: "object",
    properties: {
      filePath: { type: "string", description: ABS_PATH + " Required." },
      workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
    },
    required: ["filePath"],
  },
  handler: (args) => callKastSkill("file-outline", args),
},
```

Update `additionalContext` to include `kast_file_outline`.

### Step 6: Update AGENTS.md

Add row: `| File outline          | `kast_file_outline`                | `kast file-outline`                            |`

### Step 7: Add tests

Same pattern as 1a — add to `SkillWrapperContractTest.kt`, `SkillWrapperRequestCasingTest.kt`, `SkillCommandParsingTest.kt`.

---

## PART D: Phase 1c — Update scaffold tool description

File: `.github/extensions/kast/extension.mjs` (line 229-230)

Change the `kast_scaffold` description from:
```
"Summarize a Kotlin file/type structure (declarations, signatures, imports, key call sites) via kast scaffold. ALWAYS prefer this over reading a .kt file with `view` — scaffold returns a semantic skeleton at a fraction of the token cost."
```
to:
```
"Summarize a Kotlin file/type structure (declarations, signatures, imports, key call sites) via kast scaffold. Returns the full file content alongside the semantic skeleton — no separate `view` call needed for .kt files. ALWAYS prefer this over `view` for .kt/.kts files."
```

Also update the `suggestionFor("view")` message (around line 421) to mention that scaffold returns file content.

---

## PART E: Phase 1d — Raise Phase 2 indexing parallelism defaults

File: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt` (line 47)
- Change `IndexingPhase2Parallelism(2)` to `IndexingPhase2Parallelism(4)`

File: `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/BackgroundIndexer.kt` (line 40)
- Change `private val referenceParallelism: Int = 1` to `private val referenceParallelism: Int = 2`

The config value of 4 will override this default when config is loaded; this just makes the unconfigured path faster too.

---

## PART F: Phase 3 — Content search endpoint (replaces rg for string/comment search)

This is a new capability end-to-end. It's the last remaining reason to shell out to `rg`.

### Step 1: Define query and result types

File: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/WorkspaceSearchQuery.kt` (new file)

```kotlin
@Serializable
data class WorkspaceSearchQuery(
    val pattern: String,
    val regex: Boolean = false,
    val maxResults: Int = 100,
    val fileGlob: String? = null,
    val caseSensitive: Boolean = true,
)
```

File: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/WorkspaceSearchResult.kt` (new file)

```kotlin
@Serializable
data class WorkspaceSearchResult(
    val matches: List<SearchMatch>,
    val truncated: Boolean = false,
    override val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) : VersionedResult

@Serializable
data class SearchMatch(
    val filePath: String,
    val lineNumber: Int,
    val columnNumber: Int,
    val preview: String,
)
```

### Step 2: Add to AnalysisBackend interface

File: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/AnalysisBackend.kt`

Add: `suspend fun workspaceSearch(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult`

Add the corresponding `ReadCapability.WORKSPACE_SEARCH` entry.

### Step 3: Implement in standalone backend

File: `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackend.kt`

Implement `workspaceSearch()` that:
1. Gets all source file paths from the session
2. If the search pattern contains identifiers, uses the `MutableSourceIdentifierIndex.candidatePathsFor()` to narrow candidate files (the identifier index in `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/SourceFileIndexParser.kt` already extracts identifiers via regex)
3. Reads each candidate file and applies regex/substring matching line by line
4. Returns matches up to `maxResults`

### Step 4: Implement in IntelliJ plugin backend

File: `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/KastPluginBackend.kt`

Similar implementation using IntelliJ's VFS for file reading.

### Step 5: Implement in FakeAnalysisBackend

File: `shared-testing/src/main/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`

Add a stub implementation.

### Step 6: Add JSON-RPC dispatch

File: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt`

Add a `"workspace/search"` case following the pattern of `"workspace-symbol"` (around line 259-266).

### Step 7: Add CLI command

Files:
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandCatalog.kt` — add `workspace-search` command metadata
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandParser.kt` — add `workspaceSearchQuery()` parser
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommand.kt` — add `WorkspaceSearch` command class
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliExecution.kt` — add handler in `backendQueryHandlers`

### Step 8: Add skill wrapper

Same pattern as Phase 1a/1b:
- `SkillWrapperName.kt` — add `WORKSPACE_SEARCH("workspace-search", "kast_workspace_search")`
- `WrapperContracts.kt` — add `KastWorkspaceSearchRequest`, `KastWorkspaceSearchQuery`, `KastWorkspaceSearchSuccessResponse`, sealed `KastWorkspaceSearchResponse`
- `SkillWrapperExecutor.kt` — add `executeWorkspaceSearch()` handler
- `SkillWrapperSerializer.kt` — add serializer branch

### Step 9: Add extension tool

File: `.github/extensions/kast/extension.mjs`

```javascript
{
  name: "kast_workspace_search",
  description:
    "Search file contents across the workspace for text patterns via kast workspace-search. Supports substring and regex matching with optional file glob filtering. Use this instead of grep/rg for searching string literals, comments, and arbitrary text in Kotlin source files.",
  parameters: {
    type: "object",
    properties: {
      pattern: { type: "string", description: "Search pattern (substring or regex)." },
      regex: { type: "boolean", description: "When true, treats pattern as a regular expression." },
      maxResults: { type: "integer", description: "Maximum number of matches to return. Default 100." },
      fileGlob: { type: "string", description: "Optional glob to restrict search (e.g., '*.kt')." },
      caseSensitive: { type: "boolean", description: "Case-sensitive matching. Default true." },
      workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
    },
    required: ["pattern"],
  },
  handler: (args) => callKastSkill("workspace-search", args),
},
```

Update `additionalContext` to include `kast_workspace_search`.

### Step 10: Update AGENTS.md

Add row: `| Search file contents  | `kast_workspace_search`            | `kast workspace-search`                        |`

Update the text search whitelist (lines 98-99) to narrow `grep`/`rg` allowance to non-Kotlin files only, since `kast_workspace_search` now covers Kotlin content search.

### Step 11: Add OpenAPI spec entry

File: `docs/openapi.yaml` — add `/rpc/workspace/search` endpoint following the pattern of `/rpc/workspace-symbol`.

### Step 12: Add documentation

- `docs/reference/capabilities.md` — add `workspace/search` capability entry
- `docs/reference/api-reference.md` — add example request/response

### Step 13: Add tests

- Unit tests for the backend implementation
- Skill wrapper tests (contract, casing, parsing)
- CLI command parsing test

---

## Verification

After all changes, run:
```bash
./gradlew check
```

This will compile all modules (catching any missing `when` branches), run all tests (including the skill wrapper contract/casing/parsing tests), and validate the build.

Also manually verify the extension loads correctly by checking that `kast workspace-symbol '{"pattern":"MyClass"}'` and `kast file-outline '{"filePath":"/path/to/File.kt"}'` produce valid JSON output.
