# Analysis API agent guide

`analysis-api` owns the shared backend contract. Anything in this unit must
stay host-agnostic so the transport and runtime layers can share it.

## Ownership

Keep this unit small, stable, and reusable across every runtime host.

- Keep this module host-agnostic. Runtime-specific dependencies belong in
  runtime host modules.
- Own `AnalysisBackend`, serializable request and response models,
  `AnalysisTransport`, JSON-RPC wire models, descriptor discovery helpers,
  `ServerLaunchOptions`, shared error types, capability enums,
  `ServerInstanceDescriptor`, and edit-plan validation semantics.
- Keep shared startup helpers quiet for callers. `KastConfig.load`,
  descriptor discovery, and similar shared entry points report through typed
  results because CLI JSON commands and IDEA startup use these APIs inside
  machine-readable or UI-sensitive flows.
- Keep file-path rules explicit. Edit queries, rename hashes, workspace roots,
  and descriptor socket paths must stay absolute and normalized.
- Treat `SCHEMA_VERSION`, serialized field changes, and descriptor transport
  fields as protocol changes. Update callers, tests, and docs together when
  the wire contract moves.
- Keep materially edited public skill request and query models in matching
  files under `contract/skill`; direct sealed response variants stay with
  their sealed response root in its matching file.
- Relationship contracts carry one complete `SymbolIdentity` anchor. Exact
  success and indexed fallback require canonical declaration file and
  non-negative start offset. Identity mismatch carries a non-null actual
  identity; absence at the anchor is subject-not-found.
- Keep #337 `ReferencePageToken` and traversal handles opaque and
  host-agnostic. Source, provider position, returned-before proof, query,
  subject, semantic generation, PSI, and traversal frontier are runtime state
  and must not enter this module's wire types.
- Each relationship response root owns its degraded-reason enum. Shared
  stringly or cross-family degradation codes are prohibited. A continuation
  page must prove `KNOWN_MINIMUM >= returnedBefore + returnedCount + 1`, even
  though returned-before remains backend-private.
- `ReferenceOccurrence` owns containing-symbol evidence. Keep
  `KastScaffoldReferences` in its same-named file and never adapt occurrences
  back to bare locations.
- Keep edit application deterministic. Preserve conflict detection,
  non-overlapping range validation, and partial-apply reporting through any
  redesign.
- Shared server-held continuation stores own issued state until removal. Keep
  token/query namespaces typed, require an explicit state disposer, and dispose
  exactly once on expiry, eviction, replacement, query mismatch, explicit
  completion/invalidation, terminal consume, callback failure, and server
  shutdown. Lease/consume APIs must not return owning closeable state. A
  single-use callback returns only typed `Complete(output)` or
  `Reissue(output, nextQuery)`: complete disposes, while reissue atomically
  moves the same owned state behind a fresh handle without closing it. Claimed
  state remains store-owned through callback/shutdown races, and store close
  waits for claimed callbacks to exit and dispose; #337 IDEA
  traversal resources use this same lifecycle owner across pages.
- Use the explicit `CloseableAnalysisBackend` contract for server-owned backend
  lifetime. Do not discover closeability with a runtime cast or give runtime
  and server two independent owners.
- Keep public workspace-file continuation state distinct from raw backend
  snapshot/page state. The issue/consume identity binds the exact normalized
  root, backend, filters, projection, and limit; the owned state additionally
  binds the composition digest, last relative path, and cumulative count.
  Tokens are canonical random UUID handles. Keep the owned state and consumed
  projection as different nominal types so the generic store cannot return an
  owning state object.

## Verification

Validate the contract locally before you rely on downstream failures.

- Run `./gradlew :analysis-api:test` for local changes.
- If you change public models, capabilities, or descriptor schema, also run
  `./gradlew :analysis-server:test`.
- For public workspace-file continuation changes, start with
  `WorkspaceFilesContinuationContractTest` and
  `WorkspaceFilesContinuationServiceTest`; prove wire validation, exact-query
  single-use consumption, TTL/capacity invalidation, and state-free issue
  responses.
- If you change shared config loading, descriptor discovery, or other
  startup-facing helpers, also run `./gradlew :backend-idea:test` when the
  IDEA Platform artifacts for the pinned IDE version are available.
- For a continuation-lifecycle or cross-module workspace contract change, final
  acceptance also requires `./gradlew test` and `./gradlew buildIdeaPlugin`.
