# analysis-server

`analysis-server` is the transport and lifecycle layer between the CLI and the
analysis backend. It exposes the backend over JSON-RPC and keeps enough local
state on disk for clients to find a running daemon safely.

## Summary

This module matters because it turns the backend from an in-process library into
an operational service. It owns request dispatch, transport selection, instance
registration, and the security constraints around local server binding.

## What the wiki currently believes

- The server layer is responsible for local transport concerns, not semantic
  analysis logic.
- Descriptor persistence is central to daemon discovery and reuse.
- Stdio and Unix domain socket transports exist to cover both direct process
  invocation and reusable long-lived instances.

## Evidence and sources

The sources below describe the server's role.

- [[sources/analysis-server-json-rpc-transport-layer]] - Covers startup,
  dispatch, transports, descriptors, and errors.
- [[sources/architecture-and-module-structure]] - Places the server inside the
  broader system map.
- [[sources/kast-cli-native-cli-module]] - Shows how the CLI talks to the
  transport layer.
- [[sources/session-lifecycle-and-analysis-operations]] - Shows how the backend
  behavior becomes remotely accessible through the server.

## Related pages

The pages below connect the transport layer to the rest of the wiki.

- [[entities/kast-cli]]
- [[entities/analysis-api]]
- [[entities/backend-standalone]]
- [[concepts/client-daemon-architecture]]
- [[analyses/end-to-end-request-lifecycle]]

## Open questions

The current sources do not answer every operational question.

- What are the practical failure modes when descriptor files become stale?
- How does the server behave under multiple competing clients in the same
  workspace?
