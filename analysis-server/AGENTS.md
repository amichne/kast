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
- Do not move PSI logic, workspace discovery, or CLI parsing into this unit.
  Those belong in `backend-standalone` or `kast`.

## Verification

Prove transport changes with server tests first, then broaden if needed.

- Run `./gradlew :analysis-server:test`.
- If you change descriptor or socket lifecycle, make sure the socket transport
  tests still pass, starting with
  `./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisServerSocketTest`.
