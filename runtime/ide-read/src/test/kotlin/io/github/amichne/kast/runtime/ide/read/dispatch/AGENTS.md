# IDE read dispatch test owner

- `IdeReadRuntimeDispatchTest` proves the exact four allowed request codecs route to only their
  nominal ports and that complete, legally qualified, and semantic rejected outcomes round-trip.
- `IdeReadRuntimeDispatchNegativeTest` proves malformed, unknown, wrong-body, wrong-schema,
  response-encoding, and all eight known unsupported operation cases fail closed before any
  unintended port invocation.
- Use the production generated wire bindings to create request documents and decode responses.
  Do not hand-build JSON for a closed schema; literal replacement is permitted only to inject an
  invalid identity or unsupported canonical operation into an already generated envelope.
