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
- Keep edit application deterministic. Preserve conflict detection,
  non-overlapping range validation, and partial-apply reporting through any
  redesign.
- Shared server-held continuation stores own issued state until removal. Keep
  token/query namespaces typed, require an explicit state disposer, and dispose
  exactly once on expiry, eviction, replacement, query mismatch, explicit
  completion/invalidation, terminal consume, callback failure, and server
  shutdown. Lease/consume APIs must not return owning closeable state; #337 IDEA
  traversal resources use the same lifecycle owner.

## Verification

Validate the contract locally before you rely on downstream failures.

- Run `./gradlew :analysis-api:test` for local changes.
- If you change public models, capabilities, or descriptor schema, also run
  `./gradlew :analysis-server:test`.
- If you change shared config loading, descriptor discovery, or other
  startup-facing helpers, also run `./gradlew :backend-idea:test` when the
  IDEA Platform artifacts for the pinned IDE version are available.
- For a continuation-lifecycle or cross-module workspace contract change, final
  acceptance also requires `./gradlew test` and `./gradlew buildIdeaPlugin`.
