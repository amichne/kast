# Symbol IntelliJ adapter module guide

`:symbol:intellij` owns bounded live-IDE compilation and consumption of symbol search scopes. It
does not own request contracts, workspace-model discovery, Gradle import, persistence, mutation,
transport, or composition.

## Module map

- `IntellijSearchScopeCompiler.kt` turns admitted detached ownership and operation policy into one
  request-local `GlobalSearchScope`, then gives that capability to native query work.

## Adapter invariants

- Compile and reject scope before a PSI or index callback starts.
- Select roots only from exact Gradle/project-model ownership. Never infer generated provenance from
  paths.
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
