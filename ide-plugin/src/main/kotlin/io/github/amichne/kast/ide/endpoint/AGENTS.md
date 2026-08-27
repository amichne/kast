# IDE endpoint owner

This package owns one project-scoped Unix-domain endpoint and its descriptor-v2 publication.

- Admit the already-open IntelliJ `Project` using only generated KVP-012 compatibility, then retain
  its `AdmittedIdeProject` as `HostedIdeReadProject`; the complete runtime must supply that exact
  root and observed compatibility before binding anything.
- `ReadyIdeEndpoint` is the only readiness capability. Construct it only after atomically creating
  one exact-root exclusive state directory, binding its stable UDS, re-admitting the staged
  descriptor, and atomically moving that same physical file to the required socket-suffix path.
- Preserve every pre-existing state directory, socket, or descriptor path. Pre-ready rollback and
  READY retirement may remove only paths whose retained physical identities still match.
- Atomic creation is the cooperating Kast-writer boundary: no Kast publisher may enter, replace,
  or mutate an occupied state directory. Every child mutation must consume the directory-derived
  capability, so pre-ready rollback cannot target another Kast publisher's namespace.
- `IdeEndpointService` owns at most one ready endpoint. Duplicate publication must fail before a
  second bind. Project/plugin disposal, service cancellation, serving termination, and disposal
  racing publication must converge on the same idempotent `RetiredIdeEndpoint` transition.
- Commit initialization, cached Gradle-import, smart-mode, and all-startup-activities completion
  listeners before issuing the first attempt. The final startup signal owns one bounded,
  suspending re-observation because IntelliJ publishes cached external-project data asynchronously
  after its startup activities complete. It performs no polling or platform work. Signals during
  installation are coalesced; later signals repeat exact admission only for the four typed
  transient readiness states.
- Issue the Project endpoint generation only after the admitted Project has produced the complete
  four-port runtime. Never use semantic freshness epochs or a constant as endpoint incarnation.
- Capture the cached detached model through the suspending write-priority read boundary, prepare
  the native exact-root composition, and only then issue the endpoint generation and activate all
  four routes. A partial runtime is a named startup rejection, never a publication candidate.
- `IdeEndpointTransport` owns bounded length-prefixed framing and sequential request dispatch. A
  successful connect without a framed response is not readiness.
- Do not add project opening/import, refresh, repository traversal, blocking dispatch, persistence,
  topology, source mutation, isolated-runtime, or automatic fallback behavior.

Run the `IdeEndpointPublication` and `IdeEndpointRetirement` selectors, then
`:ide-plugin:check`, module/effect gates, and the owning receipt task.
