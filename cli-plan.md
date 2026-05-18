## Overview

Consolidate all Kast interfacing into a single RPC flow with three explicit method prefixes: `symbol/*` (orchestrated name-based), `raw/*` (direct offset-based), and `database/*` (SQLite index-driven). Delete all redundant CLI flows that won't ship in v1.0.0. Establish `VersionedCommandSpec` + generated `commands.json` as the single source of truth.

This is a large cross-cutting change. Work through the phases below in order, running tests after each phase.

---

## Phase 1: Rename RPC methods in `AnalysisDispatcher`

**File:** `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt`

In `dispatchMethod()` (lines 154-360), rename all method string keys:

### System methods (unchanged):
- `"health"` → keep
- `"runtime/status"` → keep
- `"capabilities"` → keep

### `skill/*` → `symbol/*`:
- `"skill/resolve"` → `"symbol/resolve"`
- `"skill/references"` → `"symbol/references"`
- `"skill/callers"` → `"symbol/callers"`
- `"skill/scaffold"` → `"symbol/scaffold"`
- `"skill/rename"` → `"symbol/rename"`
- `"skill/write-and-validate"` → `"symbol/write-and-validate"`

### `skill/metrics` → `database/*`:
- `"skill/metrics"` → `"database/metrics"`

### Direct methods → `raw/*`:
- `"symbol/resolve"` → `"raw/resolve"`
- `"references"` → `"raw/references"`
- `"call-hierarchy"` → `"raw/call-hierarchy"`
- `"type-hierarchy"` → `"raw/type-hierarchy"`
- `"semantic-insertion-point"` → `"raw/semantic-insertion-point"`
- `"diagnostics"` → `"raw/diagnostics"`
- `"rename"` → `"raw/rename"`
- `"imports/optimize"` → `"raw/optimize-imports"`
- `"edits/apply"` → `"raw/apply-edits"`
- `"workspace/refresh"` → `"raw/workspace-refresh"`
- `"file-outline"` → `"raw/file-outline"`
- `"workspace-symbol"` → `"raw/workspace-symbol"`
- `"workspace/search"` → `"raw/workspace-search"`
- `"workspace/files"` → `"raw/workspace-files"`
- `"implementations"` → `"raw/implementations"`
- `"code-actions"` → `"raw/code-actions"`
- `"completions"` → `"raw/completions"`

---

## Phase 2: Update `VersionedCommandSpec` as single source of truth

**File:** `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/VersionedCommandSpec.kt`

1. Update `commands()` list entries to use the new method names. Currently the entries use names like `"resolve"`, `"references"`, etc. (lines 35-126). Update to include the prefix explicitly or add a `methodName` field that includes the full RPC method path.

2. Add a `category` field to `CommandEntry` to expose the `symbol`/`raw`/`database`/`system` taxonomy in the generated `commands.json`. This way `commands.json` becomes the authoritative catalog of all RPC methods with their category, request schema, and response types.

3. Add entries for the `raw/*` methods that are currently not in `VersionedCommandSpec` (they were only in the direct CLI commands). The spec should document every RPC method the daemon supports, not just the skill-level ones.

4. Add entries for the system methods (`health`, `runtime/status`, `capabilities`).

---

## Phase 3: Delete redundant CLI commands from `CliCommandCatalog`

**File:** `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandCatalog.kt`

1. **Delete all direct analysis CLI command metadata entries** (lines ~600-870): `resolve`, `references`, `call-hierarchy`, `type-hierarchy`, `insertion-point`, `diagnostics`, `outline`, `workspace-symbol`, `workspace-search`, `implementations`, `code-actions`, `completions`.

2. **Delete all direct mutation CLI command metadata entries** (lines ~872-920): `rename`, `optimize-imports`, `apply-edits`.

3. **Delete all hidden `skill/*` wrapper command metadata entries** (lines ~1069-1155): `skill resolve`, `skill references`, `skill callers`, `skill diagnostics`, `skill rename`, `skill scaffold`, `skill write-and-validate`, `skill workspace-files`, `skill workspace-search`, `skill file-outline`, `skill workspace-symbol`.

4. **Delete all standalone `metrics` subcommand entries** (lines ~1158-1290): `metrics fan-in`, `metrics fan-out`, `metrics coupling`, `metrics low-usage`, `metrics cycles`, `metrics module-depth`, `metrics dead-code`, `metrics impact`, `metrics graph`, `skill metrics`.

5. **Delete the `removedCommandPaths` set** (lines 1383-1401) — these exist only to filter already-removed commands. With the entries fully deleted, this set is no longer needed.

6. **Simplify `activeCommands` filter** (line 1402-1403) — remove the `skill` prefix filter since those entries are gone.

7. **Delete the `METRICS` and `MUTATION_FLOW` and `ANALYSIS` command groups** from `CliCommandGroup` if they become empty after removing their commands. The `RPC` group stays (for `kast rpc`). `WORKSPACE_LIFECYCLE`, `VALIDATION`, `SHELL_INTEGRATION`, `CLI_MANAGEMENT`, `GRADLE` stay.

8. **Delete unused option metadata** — `filePathOption`, `offsetOption`, `includeBodyOption`, `includeDocumentationOption`, `includeDeclarationOption`, `includeUsageSiteScopeOption`, `directionOption`, `depthOption`, `maxTotalCallsOption`, `maxChildrenPerNodeOption`, `timeoutMillisOption`, `typeHierarchyDirectionOption`, `insertionTargetOption`, `filePathsOption`, `newNameOption`, `dryRunOption`, `patternOption`, `regexOption`, `fileGlobOption`, `caseSensitiveOption`, `maxResultsOption`, `kindOption`, `diagnosticCodeOption`, `kindFilterOption`, `metricsLimitOption`, `metricsSymbolOption`, `metricsDepthOption`, `metricsInteractiveOption` — if they are only used by deleted commands.

---

## Phase 4: Delete CLI dispatch code for removed commands

Find and delete the command dispatch/handler code in `kast-cli` that corresponds to the deleted CLI commands. Search for handler functions that match the removed command paths. The `KastCli` main dispatch (or wherever command routing happens) should only route to: `rpc`, `up`, `status`, `stop`, `install`, `self`, `completion`, `smoke`, `verify-extension`, `eval skill`, `help`, `version`, and `capabilities` (if kept as a CLI shortcut — otherwise route via `kast rpc`).

Look in `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt` and related handler files for the dispatch logic to remove.

---

## Phase 5: Update Copilot extension

**File:** `.github/extensions/kast/extension.mjs`

Update the `handler` in each tool definition to use the new RPC method names:

- `kast_workspace_files`: `callKast("workspace/files", args)` → `callKast("raw/workspace-files", args)` (line 231)
- `kast_workspace_symbol`: `callKast("workspace-symbol", args)` → `callKast("raw/workspace-symbol", args)` (line 254)
- `kast_workspace_search`: `callKast("workspace/search", ...)` → `callKast("raw/workspace-search", ...)` (line 271)
- `kast_file_outline`: `callKast("file-outline", args)` → `callKast("raw/file-outline", args)` (line 284)
- `kast_scaffold`: `callKast("skill/scaffold", args)` → `callKast("symbol/scaffold", args)` (line 303)
- `kast_resolve`: `callKast("skill/resolve", args)` → `callKast("symbol/resolve", args)` (line 320)
- `kast_references`: `callKast("skill/references", args)` → `callKast("symbol/references", args)` (line 338)
- `kast_callers`: `callKast("skill/callers", args)` → `callKast("symbol/callers", args)` (line 358)
- `kast_metrics`: `callKast("skill/metrics", args)` → `callKast("database/metrics", args)` (line 378)
- `kast_diagnostics`: `callKast("diagnostics", args)` → `callKast("raw/diagnostics", args)` (line 395)
- `kast_rename`: `callKast("skill/rename", args)` → `callKast("symbol/rename", args)` (line 419)
- `kast_write_and_validate`: `callKast("skill/write-and-validate", args)` → `callKast("symbol/write-and-validate", args)` (line 443)

---

## Phase 6: Update skill docs and agent instructions

### `.agents/skills/kast/SKILL.md`
Update all method references from `skill/*` to `symbol/*`, and from bare method names to `raw/*`. Update the routing table. Reference `commands.json` as the single source of truth for the full method catalog.

### `.agents/skills/kast/references/quickstart.md`
Update all `kast rpc` example commands to use new method names:
- `"method":"skill/resolve"` → `"method":"symbol/resolve"`
- `"method":"skill/references"` → `"method":"symbol/references"`
- `"method":"skill/callers"` → `"method":"symbol/callers"`
- `"method":"skill/scaffold"` → `"method":"symbol/scaffold"`
- `"method":"skill/rename"` → `"method":"symbol/rename"`
- `"method":"skill/write-and-validate"` → `"method":"symbol/write-and-validate"`
- `"method":"diagnostics"` → `"method":"raw/diagnostics"`
- `"method":"workspace/files"` → `"method":"raw/workspace-files"`

### `AGENTS.md`
Update the "Mandatory tool routing" section to reflect the new method taxonomy. Add a section documenting the three method families (`symbol/*`, `raw/*`, `database/*`) and when to use each. Remove references to the old `skill/*` prefix.

---

## Phase 7: Update tests

### `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`
Update all test method strings:
- `"skill/resolve"` → `"symbol/resolve"` (line 162)
- `"skill/rename"` → `"symbol/rename"` (line 183)
- `"references"` → `"raw/references"` (line 207)
- `"call-hierarchy"` → `"raw/call-hierarchy"` (line 226)
- `"symbol/resolve"` (the old offset-based) → `"raw/resolve"` (line 128)
- `"file-outline"` → `"raw/file-outline"` (line 146)
- And all other method references in test files.

### `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/PackagedSkillJsonContractTest.kt`
Update method strings in RPC request construction (line 108: `"method", "skill/resolve"` → `"method", "symbol/resolve"`).

### `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/eval/EvalSkillCommandTest.kt`
Update references if needed.

### `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/eval/adapter/SkillAdapter.kt`
Update `REQUIRED_NATIVE_TOOL_NAMES` if the tool names change, and any method routing validation.

---

## Phase 8: Create the authoritative RPC catalog resource

Create a new file or update the existing generated `commands.json` structure in `VersionedCommandSpec` to include:

1. Every RPC method grouped by category: `system`, `symbol`, `raw`, `database`
2. For each method: name, summary, request schema, response type, discriminated variants, notes, data source (backend vs sqlite)
3. Version field keyed to the CLI version

This generated `commands.json` becomes THE single source of truth. All other docs (`SKILL.md`, `quickstart.md`, `extension.mjs` descriptions, `AGENTS.md`) should reference it rather than duplicating schema details.

---

## Phase 9: Verify

1. Run `./gradlew :analysis-server:test` — confirms dispatcher routing works with new method names
2. Run `./gradlew :kast-cli:test` — confirms CLI no longer exposes deleted commands, RPC passthrough works, contract test passes
3. Run `./gradlew test` — full suite
4. Manually verify `kast rpc '{"jsonrpc":"2.0","method":"symbol/resolve","params":{"symbol":"greet"},"id":1}'` works
5. Manually verify `kast rpc '{"jsonrpc":"2.0","method":"raw/references","params":{"position":{"filePath":"...","offset":20},"includeDeclaration":true},"id":1}'` works
6. Manually verify `kast rpc '{"jsonrpc":"2.0","method":"database/metrics","params":{"metric":"fanIn"},"id":1}'` works
7. Verify `kast help` no longer shows deleted analysis/mutation/metrics commands
8. Run `kast smoke` if available to confirm end-to-end
