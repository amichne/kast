# IDE endpoint owner

This package owns one project-scoped Unix-domain endpoint and its descriptor-v2 publication.

- Admit the already-open IntelliJ `Project` using only generated KVP-012 compatibility, then retain
  its `AdmittedIdeProject` as `HostedIdeReadProject`; the complete runtime must supply that exact
  root and observed compatibility before binding anything.
- `ReadyIdeEndpoint` is the only readiness capability. Construct it only after atomically creating
  one exact-root exclusive state directory, binding its stable UDS, re-admitting the staged
  descriptor, and atomically moving that same physical file to the required socket-suffix path.
- Preserve every pre-existing state directory, socket, or descriptor path. Pre-ready rollback may
  remove only known children of the atomically created exclusive directory; KVP-025 owns READY
  lifecycle retirement.
- Atomic creation is the cooperating Kast-writer boundary: no Kast publisher may enter, replace,
  or mutate an occupied state directory. Every child mutation must consume the directory-derived
  capability, so pre-ready rollback cannot target another Kast publisher's namespace.
- `IdeEndpointService` owns at most one ready endpoint. Duplicate publication must fail before a
  second bind.
- Commit initialization, cached Gradle-import, and smart-mode listeners before issuing the first
  attempt. Signals during installation are coalesced; later signals repeat exact admission only
  for the four typed transient readiness states.
- Issue the Project endpoint generation only after the admitted Project has produced the complete
  four-port runtime. Never use semantic freshness epochs or a constant as endpoint incarnation.
- `IdeEndpointTransport` owns bounded length-prefixed framing and sequential request dispatch. A
  successful connect without a framed response is not readiness.
- Do not add project opening/import, refresh, repository traversal, blocking dispatch, persistence,
  topology, source mutation, isolated-runtime, or automatic fallback behavior.

Run the two `IdeEndpointPublication` selectors, then `:ide-plugin:check`, module/effect gates, and
the KVP-024 receipt task.
