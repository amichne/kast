# Runtime IDE-read module guide

`:runtime:ide-read` owns project-scoped admission for bounded hosted semantic reads. It is an
`IDE_READ_ONLY` module even while KVP-020's state machine remains host-neutral and pure.

## Dependency boundary

- Production depends only on `:workspace:contract` for admitted VFS-passive freshness evidence.
- Do not depend on IntelliJ distributions, `:workspace:intellij-read`, `:ide-plugin`, protocol,
  symbol, runtime composition, coroutines, or channels for KVP-020.
- Keep the workspace contract edge internal; the public permit API must not export it.

## Contract invariants

- One controller owns one Project's state; no global registry or cross-project lock is permitted.
- At most one permit is active and at most one request is queued. Further admission is a finite
  `Busy` rejection.
- Cancellation, release, disconnect, disposal, and retirement terminalize authority exactly once.
- Retained permit and queue data contain no live IntelliJ `Project`, callback, or execution effect.
- KVP-021 owns cancellable read execution; KVP-022 owns post-read epoch revalidation.

## Verification ladder

1. Run `./gradlew :runtime:ide-read:test --tests '*SingleFlightNegativeTest'`.
2. Run `./gradlew :runtime:ide-read:test --tests '*SingleFlightTest'`.
3. Run `./gradlew :runtime:ide-read:check verifyKastModuleGraph verifyForbiddenEffects`.
