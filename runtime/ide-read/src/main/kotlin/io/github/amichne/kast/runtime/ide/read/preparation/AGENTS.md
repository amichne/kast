# Hosted runtime preparation owner

This package owns the monotonic construction boundary for the exact four-operation IDE read
dispatch consumed by the project endpoint.

- `HostedIdeReadProject` accepts only the non-forgeable admitted existing Project, preserves its
  observed compatibility, and proves equality with the endpoint root without exposing the host.
- A `HostedIdeReadRuntime` exists only after all four nominal ports are supplied and retains that
  Project's endpoint root and compatibility.
- Partial construction remains a closed rejection and must never expose a dispatch capability.
- Keep transport, filesystem, lifecycle, service lookup, and endpoint publication effects out of
  this package.

Run `./gradlew :runtime:ide-read:test :runtime:ide-read:check` after changing this package.
