# Analysis API agent guide

`analysis-api` owns the shared backend contract. Anything in this unit must
stay host-agnostic so the transport and runtime layers can share it.

## Ownership

Keep this unit small, stable, and reusable across every runtime host.

- Keep this module host-agnostic. Do not add Ktor, IntelliJ Platform, or other
  runtime-specific dependencies here.
- Own `AnalysisBackend`, serializable request and response models,
  `AnalysisTransport`, JSON-RPC wire models, descriptor discovery helpers,
  `StandaloneServerOptions`, shared error types, capability enums,
  `ServerInstanceDescriptor`, and edit-plan validation semantics.
- Keep file-path rules explicit. Edit queries, rename hashes, workspace roots,
  and descriptor socket paths must stay absolute and normalized.
- Treat `SCHEMA_VERSION`, serialized field changes, and descriptor transport
  fields as protocol changes. Update callers, tests, and docs together when
  the wire contract moves.
- Keep edit application deterministic. Preserve conflict detection, non-
  overlapping range validation, and partial-apply reporting unless you are
  intentionally redesigning that behavior.

## Verification

Validate the contract locally before you rely on downstream failures.

- Run `./gradlew :analysis-api:test` for local changes.
- If you change public models, capabilities, or descriptor schema, also run
  `./gradlew :analysis-server:test :kast-cli:test :kast:compileKotlin`.
