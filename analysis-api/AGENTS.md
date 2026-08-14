# Analysis API module guide

`analysis-api` is the host-neutral contract at the base of the Kotlin runtime.
It publishes `kast-analysis-api` and the reusable test-fixtures variant. This
guide applies to the module except where the narrower
[test-fixtures guide](src/testFixtures/AGENTS.md) supplies local rules.

## Module map

- `api/client` owns workspace identity, active-install path resolution, runtime
  descriptors, launch options, and typed configuration. `client/fields` holds
  constrained configuration values grouped by concern.
- `api/continuation` owns the generic server-held continuation state machine:
  token issue, lease/consume, reissue, invalidation, disposal, capacity, TTL,
  and close.
- `api/contract/backend` defines `AnalysisBackend`, transport choices,
  capabilities, limits, health, and explicit backend closeability.
- `api/contract/query` and `api/contract/result` are the raw backend request and
  response families for analysis, workspace discovery, relationships, and
  mutation planning/execution evidence.
- `api/contract/skill` is the public agent-oriented request/response surface.
  It composes typed selectors, native read evidence, and backend results
  without owning host work.
- `api/contract/runtime` models layered readiness, lifecycle DAG edges,
  capability leases, stop permits, and runtime status.
- `api/contract/selector`, `source`, and `symbol` own exact identities, opaque
  handles, normalized paths, file images/hashes, locations, and symbol kinds.
- `api/protocol` owns JSON-RPC envelopes, stable API errors, and protocol
  exceptions. `api/validation` parses boundary models into stronger query and
  source types before backend use.
- `api/docs` derives OpenAPI and Markdown from the contract registry.
- `src/main/resources/contracts` contains checked-in contract resources;
  `src/test` proves serialization, validation, continuation, config, runtime,
  docs, and mutation evidence.

## Dependency boundary

- This module is below `analysis-server`, `index-store`, and `indexer`. It must
  not import IntelliJ/PSI, SQLite/JDBC, transport-server, process-control, or
  host-specific implementation types.
- Coroutines and serialization are contract-support dependencies, not license
  to run host effects in model code.
- `analysis-server` may expose this module's types through its API.
  `index-store` may use its normalized identities. `indexer` implements
  `AnalysisBackend` and supplies host evidence.
- The Rust CLI consumes the serialized protocol and generated artifacts under
  `cli-rs/protocol`. Kotlin package names are not the cross-language contract;
  serialized names, field sets, variants, schema versions, and generated
  schemas are.
- The checked-in version sources are
  `cli-rs/protocol/api-schema-version.txt` and
  `cli-rs/protocol/install-receipt-schema-version.txt`.
  `generateProtocolSchemaVersions` is the sole Kotlin generator for their
  constants.

## Contract invariants

- Keep raw strings, numbers, nullable platform values, JSON models, and paths
  at their owning boundary. Parsing or validation must return a stronger type
  or a closed expected failure; callers must retain that proof.
- `AnalysisBackend` receives parsed query types. Adding an operation requires
  aligned capability advertisement, backend method, router mapping, protocol
  docs, examples, and tests. Optional backend operations must fail through the
  established explicit unsupported-capability boundary.
- Treat every serialized field, serial name, default, enum/sealed variant,
  `SCHEMA_VERSION` use, descriptor field, and opaque-token shape as protocol
  state. Update generators and consumers in the same change.
- Keep workspace roots, edit paths, descriptor paths, socket paths, and file
  identities absolute and normalized at the typed boundary. Derive state roots
  through `WorkspaceIdentity` and the active CLI receipt; do not establish a
  parallel data-path authority.
- Selector handles, relationship traversal handles, and page tokens are opaque
  capabilities. Never reconstruct identity, issuer, generation, provider
  position, or traversal frontier from display data or a UUID.
- Native public read evidence reports one generation, exact or qualified
  completeness, every required stage, bounded output bytes, and prohibited
  effect counters. Legacy compatibility is an explicit variant.
- Relationship success retains one complete `SymbolIdentity` anchor. Exact or
  indexed fallback needs a canonical declaration file and non-negative start
  offset. Subject absence, identity mismatch, unsupported subject kind,
  unknown handle, and stale retained state stay distinct closed outcomes.
- Relationship result families own their degradation reason types. Preserve
  containing-symbol evidence in `ReferenceOccurrence` and preserve bounded
  cardinality evidence across continuation pages.
- `ServerHeldContinuationStore` remains the single generic owner of held
  continuation state. Owned state and projections are different nominal types.
  Complete consumes and disposes; reissue atomically moves the same state to a
  fresh handle; expiry, eviction, invalidation, callback failure, terminal
  consume, and close dispose exactly once.
- Public workspace-file continuation binds the normalized root, backend,
  filters, projection, limit, composition digest, last path, and cumulative
  count. The public token carries no resumable state and consumption is
  single-use.
- Runtime readiness has separate runtime, Gradle-model, reference, semantic
  graph, and mutation lanes. Preserve the typed lifecycle DAG and capability
  lease/stop-permit proof; an aggregate Boolean or call order is not authority.
- Diagnostics results retain one same-read-epoch hash for every analyzed file,
  including continuation pages. Never synthesize or reuse missing hash
  evidence.
- Exact mutation plans bind the admitted selector/owner, semantic generation,
  source range, exact preimage hash or file image, declaration signature, and
  complete compiler evidence required by that operation. Application and
  postcondition responses must not downgrade those facts to success flags.
- Keep edit application deterministic: normalized UTF-16 offsets,
  non-overlapping sorted edits, exact preimage/postimage replay, conflict
  detection, and explicit partial or rejected outcomes.
- `CloseableAnalysisBackend` is the server-owned backend lifetime contract.
  Do not recover ownership with runtime casts or introduce a second closer.

## Change routing

- Configuration, install-root, descriptor, or workspace-identity changes start
  in `client` and require the corresponding CLI/install contract checks.
- Backend operation or wire-model changes start in `contract` and
  `validation`, then flow outward to server routing, indexer implementation,
  Rust protocol mapping, generated docs, and fixtures.
- Generic token/store lifecycle changes start in `continuation`. Operation-
  specific continuation identity and projection stay with their result family.
- OpenAPI and Markdown generators describe source contracts; do not hand-edit a
  generated artifact to compensate for a missing model or registry entry.

## Verification ladder

1. Run the narrowest test class, for example:
   `./gradlew :analysis-api:test --tests '<fully.qualified.TestClass>'`.
2. Run `./gradlew :analysis-api:test`.
3. For public models, backend methods, descriptors, configuration, or
   continuation behavior, run `./gradlew :analysis-server:test`.
4. For OpenAPI or capability documentation, run
   `./gradlew :analysis-api:generateOpenApiSpec :analysis-api:generateDocPages`
   and the focused docs tests; use `:analysis-api:checkDocsBuild` when the site
   rendering contract changed.
5. For runtime compatibility, active-install paths, or host-facing contracts,
   run the owning shell contract and affected `:indexer:test` class.
6. Run `./gradlew test` when the change crosses more than one downstream
   module.
