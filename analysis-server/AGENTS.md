# Analysis server agent guide

`analysis-server` owns the local transport and request-dispatch layer around
`AnalysisBackend`.

## Ownership

Keep this unit focused on transport concerns around the backend interface.

- Keep the line-delimited JSON-RPC contract here. `AnalysisDispatcher`,
  `JsonRpcProtocol`, and the socket and stdio servers must agree on method
  names, error mapping, timeout behavior, and absolute-path validation.
- Preserve descriptor behavior for Unix domain socket runtimes. Starting a UDS
  server writes `ServerInstanceDescriptor` records under the configured
  descriptor directory; shutdown removes them.
- Keep capability checks, truncation, and request-limit handling aligned with
  backend responses.
- `RunningAnalysisServer` is the single backend and continuation close owner
  after start. Stop transport admission, drain dispatcher-owned continuation
  state, close the explicit `CloseableAnalysisBackend` once, and clean up
  descriptors even when one close step fails. Repeated runtime/server close must
  be idempotent; never rely on a cast to infer backend ownership. Runtime-owned
  resources outside the backend contract, such as the IDEA source-index store,
  remain with their runtime orchestrator and close only after this server owner.
- `WorkspaceFilesContinuationService` owns the internal
  `raw/workspace-files-continuation` issue/consume store. It may hold only the
  typed public continuation state supplied by the admitted Rust session; it
  never enumerates candidates or decodes state from the opaque token. Bind
  consume to the exact query identity, consume mismatches terminally, apply the
  configured TTL/capacity policy, and drain the store when the dispatcher
  closes.
- Relationship continuation state is runtime-owned. This module transports
  opaque `ReferencePageToken` and traversal handles, but must not own a second
  store, read semantic generation separately, reconstruct reference source or
  counters, or perform provider work outside the backend read-action boundary.
- Anchored relationship dispatch forwards the complete selector and handle to
  `AnalysisBackend`. `symbol/scaffold` composition preserves
  `ReferenceOccurrence.containingSymbol`; it must not collapse results to
  `List<Location>`.
- Forward family `UNSUPPORTED_SUBJECT_KIND`, cursor-invalid, and cursor-stale
  variants losslessly. The server must not infer issuer/restart history from an
  opaque UUID or perform its own subject-kind/provider preflight.
- PSI logic, workspace discovery, and CLI parsing stay in their runtime host
  and Rust CLI owners.

## Verification

Prove transport changes with server tests first, then broaden if needed.

- Run `./gradlew :analysis-server:test`.
- For continuation/backend lifetime changes, prove multi-page state remains
  open through reissue and closes exactly once on terminal/error/shutdown races.
- For public workspace-file continuation routing, run
  `WorkspaceFilesContinuationServiceTest` and
  `AnalysisServerContinuationConfigTest` before the full server suite.
- If you change descriptor or socket lifecycle, make sure the socket transport
  tests still pass, starting with
  `./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisServerSocketTest`.
