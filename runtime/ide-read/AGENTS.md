# Runtime IDE-read module guide

`:runtime:ide-read` owns project-scoped admission and the exact read-only protocol dispatch for
bounded hosted semantic reads. It is an `IDE_READ_ONLY` module even while KVP-020's state machine
remains host-neutral and pure.

## Dependency boundary

- KVP-020 production depends only on `:workspace:contract` for admitted VFS-passive freshness
  evidence. KVP-021 and KVP-022 add the narrow `:workspace:intellij-read` execution boundary.
- KVP-023 depends internally on `:protocol:wire` for the exact four generated read-operation
  bindings. Its intended KVP-024 composition consumer already owns the protocol boundary.
- KVP-029 observes the existing symbol adapter through its nominal hosted route and named native
  adapter gate; keep the product dependency edge indirect until its exact composition task. Do not
  depend on `:ide-plugin`, runtime composition, persistence, change, topology, or acquisition.
- Keep the workspace contract edge internal; the public permit API must not export it.

## Contract invariants

- One controller owns one Project's state; no global registry or cross-project lock is permitted.
- `read/admission` physically owns the finite admission outcomes and exact-root scope refinement;
  their Kotlin package remains the module's admission API.
- At most one permit is active and at most one request is queued. Further admission is a finite
  `Busy` rejection.
- Cancellation, release, disconnect, disposal, and retirement terminalize authority exactly once.
- Retained permit and queue data contain no live IntelliJ `Project`, callback, or execution effect.
- KVP-021 owns cancellable read execution; KVP-022 owns post-read epoch revalidation.
- KVP-023 owns four statically named dispatch ports. Unsupported canonical operations fail before
  generated decoding or port invocation; no collection or service locator may hold the ports.
- KVP-020..023 exact-head report and gate tasks preserve the admitted v1 prefix and are not part of
  the default `test` or `check` lifecycle. Their two report-binding test methods run only through
  those historical proof tasks; default tests still execute every host-neutral behavior case.
- `preparation/HostedIdeReadRuntime` is the sole exact-four-port construction capability consumed
  by the IDE endpoint. Partial route assembly remains a closed rejection and exposes no dispatch.
- KVP-028's workspace port is the first concrete route. It returns READY only after a same-root
  current epoch is re-admitted from the retained IDE Project and has no repair authority.
- KVP-029's discovery port enters the existing single-flight controller, invokes only the nominal
  native adapter authority, and admits detached bounded output only after same-source epoch
  equality.
- KVP-030's resolution port admits parsed candidate text only through a batch-owned selector
  capability, performs one exact semantic resolution, and verifies a non-echoed exact operation
  output after same-source epoch equality.
- KVP-031's description port admits only parsed exact-selector authority, forbids rediscovery,
  verifies the same selector in detached canonical output, and closes the exact four-port runtime
  only after the complete journey is constructible at one head.
- `kvp033RuntimeDynamicSafety` is the non-cacheable aggregate over the exact single-flight,
  cancellable-read, and epoch-revalidation selectors. It emits only JUnit evidence for the root
  KVP-033 report owner and must never become a second receipt authority.

## Verification ladder

1. Run `./gradlew :runtime:ide-read:test --tests '*SingleFlightNegativeTest'`.
2. Run `./gradlew :runtime:ide-read:test --tests '*SingleFlightTest'`.
3. Run `./gradlew :runtime:ide-read:check verifyKastModuleGraph verifyForbiddenEffects`.
4. Run `./gradlew :runtime:ide-read:kvp033RuntimeDynamicSafety` for the KVP-033 dynamic gate.
