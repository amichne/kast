# Diagnostic IntelliJ adapter module guide

`:diagnostic:intellij` owns request-local K2 diagnostic collection for an already admitted exact
scope. It does not own contracts, workspace refresh/import/publication, persistence, transport,
source writes, or mutation.

## Invariants

- Compare the requested lease with the current lease before touching PSI or K2.
- Resolve only the exact canonical files retained by the scope; never widen or infer a scope.
- Live `Project`, VFS, PSI, document, read-action, and K2 values remain request-local.
- Missing, unindexed, unsupported, or failed files qualify coverage and cannot prove absence.
- The adapter has no refresh, import, publication, transition, or write capability.

## Verification ladder

1. Run `./gradlew :diagnostic:intellij:test --tests '*DiagnosticReadTest'`.
2. Inspect and build changed Kotlin files through the exact-worktree IDEA MCP.
3. Run `./gradlew :diagnostic:service:test :diagnostic:intellij:test`.
4. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects` after architecture admission.
