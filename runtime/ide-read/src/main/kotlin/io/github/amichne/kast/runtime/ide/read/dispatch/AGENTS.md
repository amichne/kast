# IDE read runtime dispatch owner

This package owns the KVP-023 wire-to-read-port boundary.

- `IdeReadRuntimeDispatch` routes exactly workspace inspect, symbol discover, symbol resolve, and
  symbol describe through four statically named fields. Do not replace them with a collection,
  registry, service locator, or generic public binding API.
- Admit the envelope before routing. Reject the other eight known canonical operations before
  generated request decoding or port invocation.
- `IdeReadRuntimeBinding` is internal captured generic plumbing. Its only constructors are the four
  exact factories paired with `CanonicalOperationWireBindings`.
- Semantic operation rejection is an encoded response. Dispatch failures are only admission,
  unsupported operation, generated request decoding, or generated outcome encoding failures.
- Keep this owner free of Project, PSI, VFS, persistence, topology, change, runtime composition,
  process, network, and blocking-wait APIs.
