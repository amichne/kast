# Symbol IntelliJ adapter module guide

`:symbol:intellij` owns bounded live-IDE compilation and consumption of symbol search scopes. It
does not own request contracts, workspace-model discovery, Gradle import, persistence, mutation,
transport, or composition.

## Module map

- `IntellijSearchScopeCompiler.kt` turns admitted detached ownership and operation policy into one
  request-local `GlobalSearchScope`, then gives that capability to native query work.

## Adapter invariants

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

## Verification ladder

1. Run `./gradlew :symbol:intellij:test --tests '*SourceRoot*PolicyTest'`.
2. Reformat and inspect every changed Kotlin file through the exact-worktree IDEA MCP.
3. Build the changed files through IDEA.
4. Run `./gradlew :symbol:intellij:test`.
5. Run `./gradlew verifyKastArchitecture --configuration-cache`.
