# Analysis server module guide

`analysis-server` publishes `kast-analysis-server` and owns local JSON-RPC
admission, routing, transport, and server-side orchestration around an
`AnalysisBackend`. It does not own compiler analysis, index persistence, or
process lifecycle selection.

## Module map

- `AnalysisServer` and `AnalysisServerConfig` assemble a backend, dispatcher,
  transport, descriptor registry, limits, continuation policy, and optional
  runtime capability leases.
- `PublicSymbolReadBinding` selects the legacy backend route or one narrow
  native symbol reader with its exact root and selector-handle authority.
- `DescriptorStore` owns Unix-socket bind admission, descriptor registration,
  exact endpoint ownership checks, stale endpoint removal, and stop-permit
  revalidation.
- `dispatch` parses JSON-RPC, maps raw and agent-oriented methods, enforces
  advertised capabilities, selects deadline authority, maps typed failures,
  and bounds concurrent requests.
- `skill` projects public discover, resolve, relationship, scaffold, rename,
  add, write, and replacement operations onto the backend.
- `mutation` provides canonical request fingerprinting and in-process
  idempotent submission for `KastSemanticMutation`.
- `transport` implements stdio, TCP, and Unix-domain socket servers,
  cross-process trace-envelope admission, and `RunningAnalysisServer`.
- `WorkspaceFilesContinuationService` owns the public workspace-file
  continuation store; relationship continuation state remains backend-owned.
- `src/test` mirrors dispatcher families, transport/socket lifecycle,
  descriptor ownership, continuation behavior, config, mutation idempotency,
  and generated examples.

## Dependency boundary

- `analysis-api` is an `api` dependency and therefore the only public model
  surface this module should expose.
- `index-store` is an implementation dependency, but SQLite schema, queries,
  snapshots, and generation authority remain in that module. Do not move
  persistence into dispatch or transport.
- A concrete backend is supplied from `indexer` at runtime. This module must
  not import IntelliJ, Kotlin PSI/compiler, Gradle project-model, or workspace
  indexing implementation types.
- CLI parsing, semantic-demand routing, process ownership, install state, and
  runtime-epoch selection remain outside this module. The server only validates
  the exact descriptor/socket/permit facts handed to its local boundary.

## Server invariants

- Parse wire input once. `RpcMethodRouter` must decode to the typed
  query/request model and require the advertised read or mutation capability
  before invoking the backend. Unknown methods and invalid params remain
  distinct JSON-RPC errors.
- Keep raw methods and public skill methods aligned with `AnalysisBackend`,
  capability enums, serializers, OpenAPI/docs, and fixtures.
  The server transports results; it must not manufacture missing semantic
  evidence.
- Native public discover and resolve calls use only `PublicSymbolReadBinding`.
  Exact resolve rejects qualified coverage. The reader must issue selector
  handles before its generation admission closes.
- `RpcRequestWaitPolicy` is the sole deadline selector. Ordinary calls use the
  effective server deadline, semantic-graph recovery has one finite outer
  transition budget, and progress-governed mutation/refresh calls retain the
  backend deadline authority.
- Keep request concurrency admission and runtime capability leases paired.
  Every admitted request releases its lease on success, typed failure,
  cancellation, timeout, or dispatcher shutdown.
- `kastTrace` is optional transport metadata. If present, admit its complete
  exact field set, request-ID equality, canonical invocation identity, and
  non-zero trace/span IDs before removing it from the stable JSON-RPC request.
  Malformed trace data never reaches backend dispatch.
- Preserve the full same-epoch diagnostics hash map and relationship identity,
  occurrence, limitation, degradation, cursor-invalid, and cursor-stale
  variants. Do not collapse them to locations, strings, or generic errors.
- `WorkspaceFilesContinuationService` may store only the typed public state
  admitted by the caller. Tokens are opaque and single-use, query mismatch is
  terminal, TTL/capacity are enforced, and dispatcher close drains the store.
  The service never enumerates candidates or reconstructs state from a token.
- Relationship continuation state and provider work remain inside the backend
  read authority. Do not add a second relationship store or independently read
  semantic generation in this module.
- `MutationExecutionService` binds an idempotency key to the canonical SHA-256
  fingerprint of one typed mutation. Reuse with the same fingerprint shares
  the completed outcome; reuse with different content is a conflict.
- Non-loopback TCP binding requires a non-empty token. Unix socket setup uses
  a private descriptor directory and proves socket inode, owner UID, process
  identity, runtime instance, workspace, and descriptor stability before
  registration or stale removal.
- `RunningAnalysisServer` is the single close owner after start. Close
  transport admission, then dispatcher-held state, backend, capability
  registry, and descriptor; aggregate suppressed failures and make repeated
  close idempotent.
- A stop permit authorizes shutdown only after both the lease registry and
  `DescriptorStore` revalidate the same runtime epoch, process, registration,
  descriptor, socket inode, and owner. Ambiguity rejects shutdown.

## Change routing

- Wire shape and backend method changes start in `analysis-api`, then update
  `dispatch` and `skill` here.
- Transport framing, trace metadata, endpoint permissions, or descriptor
  ownership changes stay in `transport`/`DescriptorStore` and require socket
  lifecycle tests.
- Operation orchestration belongs under `skill` or `mutation`; compiler,
  persistence, workspace refresh, and mutation proof construction stay in the
  backend.
- Generated examples are test-source outputs of the real router. Update the
  route and typed fixture before regenerating examples.

## Verification ladder

1. Run the focused class, for example:
   `./gradlew :analysis-server:test --tests '<fully.qualified.TestClass>'`.
2. Run `./gradlew :analysis-server:test`.
3. For wire or shared contract changes, also run
   `./gradlew :analysis-api:test`.
4. For descriptor, close, deadline, capability-lease, continuation, or
   backend-routing changes, run the affected `:indexer:test` class.
5. For example generation, run its focused contract test.
6. Run `./gradlew test` when the change spans server, backend, and persistence.
