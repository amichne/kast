# Topology IntelliJ adapter module guide

`:topology:intellij` owns explicit admitted-root enumeration and request-local K2 extraction of
detached topology facts. It owns no build coordination, publication, database, Gradle import,
module scan, repository-root walk, startup hook, or read-triggered work.

## Invariants

- Candidate enumeration starts only from `PublishedWorkspace.sourceRoots`, includes only `.kt`
  and `.kts` files, requires one exact Gradle source-set owner, and deduplicates paths.
- Each extraction call consumes one admitted candidate and returns only detached compiler symbols,
  compiler-confirmed intersymbol edges, and a terminal `Complete` or typed failure.
- One exact content-identified candidate generation may share one detached projection registry;
  changed candidate evidence invalidates reuse, and each terminal extraction reloads only its
  requested file.
- Compiler identities are source-scoped and include callable receiver types where Kotlin overload
  identity requires them.
- Live `Project`, VFS, PSI, search, and K2 values remain inside explicit adapter calls.
- Local declarations and intrafunctional dataflow are outside the topology model.

## Verification

1. Run `./gradlew :topology:intellij:test`.
2. Run the installed enterprise topology acceptance for real K2 coverage.
3. Run `./gradlew verifyTopologyAuthority verifyForbiddenEffects`.
