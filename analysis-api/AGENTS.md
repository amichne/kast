# Analysis API agent guide

`analysis-api` owns the shared backend contract. Anything in this unit must
work for both the IntelliJ host and the standalone host.

## Ownership

Keep this unit small, stable, and reusable across every runtime host.

- Keep this module host-agnostic. Do not add Ktor, IntelliJ Platform, or other
  runtime-specific dependencies here.
- Own `AnalysisBackend`, serializable request and response models, shared error
  types, capability enums, and edit-plan validation semantics.
- Keep file-path rules explicit. The current contract requires absolute,
  normalized paths for edit planning and transport validation.
- Treat `SCHEMA_VERSION` and serialized field changes as protocol changes.
  Update callers, tests, and docs together when the wire contract moves.
- Keep edit application deterministic. Preserve conflict detection, non-
  overlapping range validation, and partial-apply reporting unless you are
  intentionally redesigning that behavior.

## Verification

Validate the contract locally before you rely on downstream failures.

- Run `./gradlew :analysis-api:test` for local changes.
- If you change public models or capabilities, also run the dependent module
  tests that exercise the contract.
