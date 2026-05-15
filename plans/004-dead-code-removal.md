## Objective

Remove all defunct code: the entire skill wrapper package, per-operation CLI commands (ANALYSIS and MUTATION_FLOW
groups), wrapper contract types, the OpenAPI document generator, and all associated tests. After this phase, the only
way to invoke analysis/mutation operations is through `kast rpc`.

## Repository: michne/kast

## IMPORTANT DESIGN DECISION

Before executing this phase, determine what to do about the 7 tools in `extension.mjs` that depend on wrapper
orchestration logic (`resolve` by name, `references` by name, `callers` by name, `scaffold`, `rename`,
`write-and-validate`, `metrics`). These tools use `SkillWrapperExecutor` which provides:

1. Name-to-position resolution via `NamedSymbolResolver` (workspace-symbol search → resolve → get offset)
2. Multi-step orchestration (scaffold = outline + resolve + references + type-hierarchy + insertion-point + file-read)
3. Rename = dry-run + apply-edits + diagnostics
4. Write-and-validate = apply-edits + optimize-imports + diagnostics

**Option A (recommended):** Move the orchestration logic into new JSON-RPC methods in `AnalysisDispatcher` (e.g.,
`skill/resolve`, `skill/references`, `skill/scaffold`, `skill/rename`, `skill/write-and-validate`). This keeps the CLI
thin and puts orchestration server-side.

**Option B:** Move the orchestration into `extension.mjs` (multiple sequential `kast rpc` calls per tool invocation).
This is simpler but slower (multiple process spawns per tool call).

**Option C:** Keep a thin orchestration layer in the CLI that's NOT the skill wrapper — a new `CliCommand.Rpc` handler
that intercepts certain method names and orchestrates locally. This is what `CliService` already does but without the
wrapper contract types.

**Go with Option A** — add orchestration methods to the daemon. This is the cleanest because it makes `kast rpc` the
single entry point with no client-side orchestration.

## Files to DELETE entirely

### Skill wrapper package (8 files)

- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperExecutor.kt`
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperName.kt`
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperSerializer.kt`
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperInput.kt`
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/NamedSymbolResolver.kt`
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillLogFile.kt`
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/MetricsResultEncoder.kt`
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/InstallSkillResult.kt` — check if this is used by
  InstallSkillService; if so, move it to the `results/` package first

### Wrapper contract types

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/wrapper/WrapperContracts.kt` — delete the entire file (753
  lines of wrapper request/response/query types)

### OpenAPI document generator

- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/WrapperOpenApiDocument.kt`

### Test files (6 files)

- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillCommandParsingTest.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperContractTest.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperDiscriminatorTest.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperInputTest.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperRequestCasingTest.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperSerializerTest.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/WrapperOpenApiDocumentTest.kt`

### Packaged wrapper-openapi files

- `.agents/skills/kast/references/wrapper-openapi.yaml`
- `.agents/skills/kast/fixtures/maintenance/references/wrapper-openapi.yaml`

## Files to MODIFY

### 1. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommand.kt`

- Remove ALL `BackendQuery<Q>` subtypes (lines 30-59): `WorkspaceRefresh`, `ResolveSymbol`, `FindReferences`,
  `CallHierarchy`, `TypeHierarchy`, `SemanticInsertionPoint`, `Diagnostics`, `FileOutline`, `WorkspaceSymbol`,
  `WorkspaceSearch`, `WorkspaceFiles`, `Implementations`, `CodeActions`, `Completions`, `Rename`, `ImportOptimize`,
  `ApplyEdits`
- Remove `BackendQuery<Q>` interface itself
- Remove `Skill` data class
- Remove `EvalSkill` data class (if eval can be refactored to not depend on skill wrappers — check
  `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/eval/adapter/SkillAdapter.kt` which has 20 matches for
  SkillWrapper references)
- Keep: `Help`, `Version`, `VerifyExtension`, `Completion`, `WorkspaceStatus`, `WorkspaceEnsure`, `WorkspaceStop`,
  `Capabilities`, `Install`, `InstallSkill`, `InstallCopilotExtension`, `SelfStatus`, `SelfDoctor`, `SelfUninstall`,
  `SelfUpgrade`, `Smoke`, `DaemonStart`, `ConfigInit`, `GradleRun`, `Metrics`, `Rpc`, `Up`, `Status`, `Stop` (from Phase
  1)

### 2. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandParser.kt`

- Remove `parseSkillCommand` method (lines 230-249)
- Remove `parseDirectSkillWrapperCommand` method (lines 252-265)
- Remove the two calls to these methods (lines 73-76)
- Remove the `import io.github.amichne.kast.cli.skill.SkillWrapperName` import
- Remove all analysis/mutation command branches from `parseKnownCommand` (lines 104, 107-131): `workspace files`,
  `resolve`, `references`, `call-hierarchy`, `type-hierarchy`, `insertion-point`, `diagnostics`, `outline`,
  `workspace-symbol`, `workspace-search`, `implementations`, `code-actions`, `completions`, `rename`,
  `optimize-imports`, `apply-edits`
- Remove all query builder methods from `ParsedArguments` (lines 336-593): `symbolQuery`, `referencesQuery`,
  `diagnosticsQuery`, `callHierarchyQuery`, `typeHierarchyQuery`, `semanticInsertionQuery`, `renameQuery`,
  `applyEditsQuery`, `importOptimizeQuery`, `workspaceFilesQuery`, `implementationsQuery`, `codeActionsQuery`,
  `completionsQuery`, `refreshQuery`, `fileOutlineQuery`, `workspaceSymbolQuery`, `workspaceSearchQuery`
- Remove all analysis-specific imports at the top (lines 3-27)
- Keep: `workspace status`, `workspace ensure`, `workspace stop`, `capabilities` (useful for debugging),
  `completion bash/zsh`, `install *`, `self *`, `smoke`, `daemon start`, `config init`, `eval skill`, `gradle run`,
  `metrics *`, `rpc`, `up`, `status`, `stop`

### 3. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliExecution.kt`

- Remove the entire `backendQueryHandlers` map (lines 52-121)
- Remove the `executeBackendQuery` method (lines 321-337)
- Remove the `is CliCommand.BackendQuery<*>` branch (line 163)
- Remove the `is CliCommand.Skill` branch (lines 230-237)
- Remove imports for `SkillWrapperExecutor` and `SkillWrapperSerializer`
- Remove `is CliCommand.EvalSkill` branch IF eval is being removed/refactored

### 4. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliService.kt`

- Remove ALL 16 per-operation methods (lines 118-296): `resolveSymbol`, `findReferences`, `callHierarchy`,
  `typeHierarchy`, `diagnostics`, `fileOutline`, `workspaceSymbolSearch`, `workspaceFiles`, `workspaceSearch`,
  `implementations`, `codeActions`, `completions`, `semanticInsertionPoint`, `rename`, `optimizeImports`, `applyEdits`
- Remove `workspaceRefresh` (line 92-102)
- Remove `requireReadCapability` and `requireMutationCapability` private methods — OR keep them if `capabilities`
  command still needs them
- Remove all the query/result type imports (lines 3-40)
- Keep: `workspaceStatus`, `workspaceEnsure`, `workspaceStop`, `capabilities`, `install`, `installSkill`,
  `installCopilotExtension`, `selfStatus`, `selfDoctor`, `selfUninstall`, `selfUpgrade`, `smoke`, `daemonStart`,
  `configInit`, `rpcPassthrough` (from Phase 1)

### 5. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandCatalog.kt`

- Remove `ANALYSIS` and `MUTATION_FLOW` from `CliCommandGroup` enum (lines 15-22)
- Remove ALL CliCommandMetadata entries with group ANALYSIS (lines 595-866): `capabilities`, `resolve`, `references`,
  `call-hierarchy`, `type-hierarchy`, `insertion-point`, `diagnostics`, `outline`, `workspace-symbol`,
  `workspace-search`, `implementations`, `code-actions`, `completions`
- Remove ALL CliCommandMetadata entries with group MUTATION_FLOW (lines 868-918): `rename`, `optimize-imports`,
  `apply-edits`
- Remove ALL hidden `skill *` entries (lines 1065-1153, 1278-1286)
- Remove all unused `CliOptionMetadata` definitions that were only used by deleted commands (e.g., `filePathOption`,
  `offsetOption`, `includeBodyOption`, `includeDocumentationOption`, `directionOption`, `depthOption`,
  `maxResultsOption`, `maxTotalCallsOption`, `maxChildrenPerNodeOption`, `timeoutMillisOption`, `newNameOption`,
  `includeDeclarationOption`, `includeUsageSiteScopeOption`, `insertionTargetOption`, `dryRunOption`, `patternOption`,
  `regexOption`, `fileGlobOption`, `caseSensitiveOption`, `kindOption`, `kindFilterOption`, `diagnosticCodeOption`,
  `filePathsOption`). Keep options that are still used by retained commands.
- Update the top-level help text "Try" section to show `kast rpc` instead of `kast diagnostics`

### 6. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/eval/adapter/SkillAdapter.kt`

This file has 20 references to SkillWrapper types. Options:

- If `eval skill` is being kept, refactor `SkillAdapter` to use `kast rpc` internally instead of `SkillWrapperExecutor`
- If `eval skill` depends too heavily on wrapper types, consider removing it or deferring its refactor

### 7. `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/EmbeddedSkillResources.kt`

- Remove `"references/wrapper-openapi.yaml"` from the MANIFEST list (line 42)
- Remove `"fixtures/maintenance/references/wrapper-openapi.yaml"` from the MANIFEST list (line 38)

### 8. `.github/extensions/kast/extension.mjs`

- Complete the migration from Phase 2: switch ALL remaining tool handlers from `callKastSkill` to `callKast`
- Delete `callKastSkill` function entirely
- This requires that the daemon now supports the orchestration methods (Option A from above)

### 9. `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/PackagedSkillJsonContractTest.kt`

- Remove any assertions about wrapper-openapi.yaml or wrapper schema presence

### 10. `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/eval/EvalSkillCommandTest.kt`

- Update if eval was refactored; remove if eval was removed

### 11. `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/eval/adapter/SkillAdapterTest.kt`

- Update if eval was refactored; remove if eval was removed

## Adding daemon-side orchestration methods (Option A)

### `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt`

Add new JSON-RPC methods that provide the orchestration currently in `SkillWrapperExecutor`:

- `skill/resolve` — accepts `{symbol, fileHint?, kind?, containingType?}`, does workspace-symbol search + resolve
  internally
- `skill/references` — same name resolution + find-references
- `skill/callers` — name resolution + call-hierarchy
- `skill/scaffold` — outline + resolve + references + type-hierarchy + insertion-point + file-read
- `skill/rename` — name resolution + dry-run rename + apply-edits + diagnostics
- `skill/write-and-validate` — apply-edits + optimize-imports + diagnostics
- `skill/metrics` — direct metrics engine query

These methods would accept the same request shapes currently defined in `WrapperContracts.kt` (or simplified versions).
Move the orchestration logic from `SkillWrapperExecutor` into new server-side handlers rather than deleting it entirely.

**Alternative (simpler):** If adding daemon-side methods is too large for this phase, keep a thin `RpcOrchestrator`
class in `kast-cli` that provides the same multi-step orchestration but uses `rpcPassthrough` internally. This avoids
touching the daemon at all.

## Verification gate

- `./gradlew build` compiles with zero references to deleted types
- `./gradlew test` passes
-
`grep -rE "SkillWrapperName|SkillWrapperExecutor|parseSkillCommand|parseDirectSkillWrapper|wrapper-openapi|callKastSkill" kast-cli/src/main/ .github/extensions/`
returns zero matches
- `kast help` shows ONLY: `up`, `status`, `stop`, `rpc`, `workspace status`, `workspace ensure`, `workspace stop`,
  `capabilities`, `smoke`, `metrics *`, `install *`, `self *`, `daemon start`, `config init`, `completion *`,
  `gradle run`, `eval skill`, `verify-extension`
- `kast help` does NOT show: `resolve`, `references`, `call-hierarchy`, `type-hierarchy`, `insertion-point`,
  `diagnostics`, `outline`, `workspace-symbol`, `workspace-search`, `implementations`, `code-actions`, `completions`,
  `rename`, `optimize-imports`, `apply-edits`, `skill *`, `workspace files`, `workspace refresh`
- All 12 `kast_*` Copilot extension tools still work
