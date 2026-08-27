# Runtime IDE-read module guide

`:runtime:ide-read` owns project-scoped admission and the exact read-only protocol dispatch for
bounded hosted semantic reads. It is an `IDE_READ_ONLY` module even while KVP-020's state machine
remains host-neutral and pure.

## Dependency boundary

- KVP-020 production depends only on `:workspace:contract` for admitted VFS-passive freshness
  evidence. KVP-021 and KVP-022 add the narrow `:workspace:intellij-read` execution boundary.
- KVP-023 depends internally on `:protocol:wire` for the exact four generated read-operation
  bindings. Its intended KVP-024 composition consumer already owns the protocol boundary.
- Do not depend on `:ide-plugin`, runtime server/composition, workspace or symbol services,
  persistence, change, topology, runtime acquisition, coroutines, or channels.
- Keep the workspace contract edge internal; the public permit API must not export it.

## Contract invariants

- One controller owns one Project's state; no global registry or cross-project lock is permitted.
- At most one permit is active and at most one request is queued. Further admission is a finite
  `Busy` rejection.
- Cancellation, release, disconnect, disposal, and retirement terminalize authority exactly once.
- Retained permit and queue data contain no live IntelliJ `Project`, callback, or execution effect.
- KVP-021 owns cancellable read execution; KVP-022 owns post-read epoch revalidation.
- KVP-023 owns four statically named dispatch ports. Unsupported canonical operations fail before
  generated decoding or port invocation; no collection or service locator may hold the ports.
- `preparation/HostedIdeReadRuntime` is the sole exact-four-port construction capability consumed
  by the IDE endpoint. Partial route assembly remains a closed rejection and exposes no dispatch.

## Verification ladder

1. Run `./gradlew :runtime:ide-read:test --tests '*SingleFlightNegativeTest'`.
2. Run `./gradlew :runtime:ide-read:test --tests '*SingleFlightTest'`.
3. Run `./gradlew :runtime:ide-read:check verifyKastModuleGraph verifyForbiddenEffects`.
