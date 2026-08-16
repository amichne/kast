# Symbol IntelliJ adapter module guide

`:symbol:intellij` owns bounded live-IDE compilation and consumption of symbol search scopes. It
does not own request contracts, workspace-model discovery, Gradle import, persistence, mutation,
transport, or composition.

## Module map

- `IntellijSearchScopeCompiler.kt` turns admitted detached ownership and operation policy into one
  request-local `GlobalSearchScope`, then gives that capability to native query work.
- `IntellijNativeDiscoveryAdapter.kt` runs restartable discovery reads. Its fast adapter combines
  Kotlin contributor discovery, exact selection, revalidation, and detached projection in one read.
- `IntellijNativeDiscoveryQuery.kt` performs platform matching, scoped native name/element
  processing, bounded collection, deterministic ordering, qualification, and timing.
- `IntellijSymbolCompilerAdapter.kt` exposes the detached compiler result while retaining all live
  IntelliJ values inside the request call.
- `IntellijKotlinCompilerSymbolLookup.kt` resolves one exact Kotlin declaration through K2 and
  detaches its overload-aware compiler identity before the analysis session ends.
- `IntellijSymbolSelectorResolver.kt` recompiles the retained scope and issues or revalidates the
  canonical `SymbolSelector` inside one restartable read.
- `IntellijSymbolExactCompilerAdapter.kt` exposes host-neutral resolve/describe compiler results
  while retaining every live IntelliJ and K2 value inside the request call.
- `IntellijDiscoveryProjection.kt` converts only already-in-scope live items into detached
  generation-bound candidates.
- `IntellijExactSelectorResolver.kt` admits the current root/generation, recompiles the discovery
  scope, and issues or revalidates an exact selector inside one restartable read.
- `IntellijPsiExactDeclarationLookup.kt` resolves the candidate's retained file/name/offset to one
  scope-contained declaration and detaches exact range, qualified-identity state, and runtime type.
- `IntellijNativeRelationAdapter.kt` admits the current selector lease, recompiles its retained
  scope, and executes one bounded relation read inside a restartable IntelliJ read action.
- `IntellijNativeRelationQuery.kt` owns relation limits, deterministic collection, explicit
  terminal versus nonterminal coverage, and closed provider/environment qualifications.
- `IntellijPsiNativeRelationSearch.kt` revalidates the exact live subject and performs one-hop
  reference, definition, or outgoing-reference traversal through native IntelliJ facilities.
- `IntellijPsiRelationFactProjector.kt` detaches live related declarations and occurrences into
  generation/scope-bound exact relation facts.

## Adapter invariants

- The published archive name is `symbol-intellij`; portable runtime verification depends on this
  unambiguous module identity.
- Compile and reject scope before a PSI or index callback starts.
- Select roots and production/test/generated classification only from exact Gradle project-model
  ownership. Never infer classification from paths or source-set names.
- Exact-file scope must resolve to one most-specific model root. Unknown or multiply owned target
  provenance is a closed rejection before native work.
- Admit libraries only for an explicit workspace-wide policy through
  `ProjectScope.getLibrariesScope`, whose platform contract is backed by project-file-index
  library membership; never turn library readability into source or edit authority.
- Bind every compiled capability to the request lease's canonical root and generation.
- Keep `Project`, `VirtualFile`, and `GlobalSearchScope` internal and request-local. Retain no PSI
  or other live IDE object across requests.
- Ordinary reads do not refresh, import Gradle, write files, mutate PSI, persist evidence, build a
  graph, or control processes.
- Use only scoped `ChooseByNameContributorEx` processing. A legacy scope-blind contributor is an
  explicit qualified limitation, never a fallback enumeration.
- Collect matching names before requesting elements. Nested stub-index operations can deadlock and
  are prohibited.
- The Kotlin fast path admits only the supported Kotlin declaration contributors. Unwrap
  `PsiElementNavigationItem` through its target before scope and projection checks.
- Check cancellation and dumb/project state during provider streaming. Platform cancellation must
  escape the query so the write-priority `readAction` can restart or cancel truthfully.
- Apply record, byte, work, and elapsed limits before retaining live items; every nonterminal cap
  remains visible in the detached outcome.
- Exact resolution must consume a batch-owned selection. Missing files/elements, scope rejection,
  multiple matching PSI ancestors, unsupported declarations, root/generation drift, and changed
  native evidence are distinct closed failures; never guess among collisions.
- Canonical exact selectors require K2 identity. PSI names, qualified names, offsets, runtime
  implementation classes, and navigation items cannot issue `SymbolSelector` authority.
- Overload identity includes compiler callable ownership, receiver, context receivers, parameter
  types, and type-parameter arity; failure to detach that identity is a closed rejection.
- Relation search receives only the selector's compiled scope. Use `ReferencesSearch` for exact
  resolved references, `DefinitionsScopedSearch` with deep traversal disabled for native
  definitions, and bounded PSI reference walking for outgoing targets; never widen scope or accept
  same-name text as resolution.
- Check cancellation inside every relation processor and walker. A provider cap or halt,
  unresolved target, unsupported item, dumb transition, or provider failure yields qualified
  known-minimum coverage; only limitation-free terminal enumeration may yield an exact count.
- Relation facts detach exact endpoint evidence and absolute source occurrences. Live PSI,
  references, virtual files, and queries never survive the request-local read.

## Verification ladder

1. Run `./gradlew :symbol:intellij:test --tests '*SourceRoot*PolicyTest' --tests '*NativeDiscoveryTest' --tests '*ExactSelectorResolutionTest' --tests '*NativeRelationReadTest'`.
2. Reformat and inspect every changed Kotlin file through the exact-worktree IDEA MCP.
3. Build the changed files through IDEA.
4. Run `./gradlew :symbol:intellij:test`.
5. Run `./gradlew verifyKastArchitecture --configuration-cache`.
