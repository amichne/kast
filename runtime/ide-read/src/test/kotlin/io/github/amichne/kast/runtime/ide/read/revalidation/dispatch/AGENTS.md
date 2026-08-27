# IDE read dispatch test owner

- `IdeReadRuntimeDispatchTest` proves the exact four allowed request codecs route to only their
  nominal ports and that complete, legally qualified, and semantic rejected outcomes round-trip.
- `IdeReadRuntimeDispatchNegativeTest` proves malformed, unknown, wrong-body, wrong-schema,
  response-encoding, and all eight known unsupported operation cases fail closed before any
  unintended port invocation.
- Use the production generated wire bindings to create request documents and decode responses.
  Do not hand-build JSON for a closed schema; literal replacement is permitted only to inject an
  invalid identity or unsupported canonical operation into an already generated envelope.
- `IdeHostedWorkspaceInspectNegativeProof` rejects the isolated-host candidate and unavailable
  epoch without repair. `IdeHostedWorkspaceInspectAcceptance` proves exact root, IDE host,
  admitted capabilities, and current-epoch success through the production KVP-028 port.
- `IdeHostedSymbolDiscoverNegativeProof` proves isolated host, movement, excess results, dumb-mode
  qualification, and cancellation cannot appear as complete KVP-029 discovery.
- `IdeHostedSymbolDiscoverAcceptance` proves the current exact Project capability reaches one
  bounded detached native discovery outcome and remains current through projection.
