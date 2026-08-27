# Existing-Project admission source guide

This package owns the transition from a live IntelliJ `Project` to `AdmittedIdeProject` and the
state-specific transitions from that capability to `DetachedIdeWorkspaceModel` and an opaque
`ProjectReadEpoch` observation.

## Invariants

- Keep the live `Project` behind `LiveProjectHandle`; no public JVM member may expose `Project`.
- Observe lifecycle, exact root, cached Gradle model, dumb mode, K2 mode, and compatibility in that
  order. A rejection stops every later observation, while `ProcessCanceledException` propagates.
- Compare platform path text only with the supplied `CanonicalWorkspaceRoot`. Relative,
  non-normalized, malformed, and missing paths are unavailable; do not manufacture a canonical
  proof with `toAbsolutePath`, filesystem canonicalization, refresh, or repair.
- `LiveExistingProjectObservation` may read cached IntelliJ/Gradle state only. Do not open a
  Project, link or import Gradle, refresh VFS, wait for indexing, walk the repository, or hash
  sources.
- `LiveDetachedModelCapture` is the sole KVP-016 live adapter. The installed endpoint uses its
  suspending write-priority `readAction` transition; the historical synchronous test boundary
  rejects EDT entry before `ReadAction.computeCancellable`. Inside either boundary, recheck
  cancellation, disposal, open state, initialization, dumb mode, root, cached Gradle state, and
  every collection bound.
- Refine primitive observations into bounded identity and path types. Reject duplicate or
  conflicting source roots, cross-module ownership ambiguity, and duplicate classpath URLs.
- Admit at most 256 detached IntelliJ modules. This covers the canonical Kast model's 132 cached
  Gradle modules while preserving a finite rejection above the evidence-backed capacity.
- Admit at most 2,048 detached classpath identities per module. This bounds the supported IDEA
  host's 1,785 physical JAR surface plus module outputs without truncating the canonical model.
- Preserve each Java code/resource source folder's explicit cached `isForGeneratedSources` flag;
  missing or conflicting provenance is a closed capture rejection and must never be inferred from
  a path name.
- Preserve the exact `Project.basePath` match in `ExactObservedWorkspaceRoot`; detached model
  construction must consume that proof instead of recreating or discarding it.
- Admit classpath URLs only as exact IntelliJ `file://`, `jar://`, or `jrt://` plus raw VFS path.
  Preserve spaces and literal `%`, `?`, and `#` path characters. Reject unsupported protocols,
  opaque forms, authority/doubled separators, dot segments, malformed archive separators, and
  trailing separators except the canonical jar archive-root `!/`.
- Preserve non-empty module, unique-name, and unambiguous source-root ownership proof in
  `RefinedDetachedModules`; the workspace-model constructor must consume that aggregate.
- The public detached surface may expose only immutable host-neutral values and unmodifiable
  collections. It must not retain a Project, Module, VirtualFile, PSI, Gradle DataNode, callback,
  mutable collection, or generic platform wrapper.
- `AdmittedIdeProject` retains exactly one private Project/runtime epoch source. Its listeners live
  with the Project connection, and each observation performs one short cancellable read over
  cached model, PSI, root-model, dumb-mode tracker, and root-filtered VFS evidence.
- Construct that contract-internal source only through the module's explicit Kotlin friend path;
  keep its constructor private and its internal construction/observation surface JVM-synthetic.
- Keep primitive counters, raw paths, listener callbacks, and platform types inside this adapter.
  Expose only opaque epoch observations; reject EDT, dumb, moved lifecycle/root/model, exhausted
  counters, malformed VFS paths, and read preemption as finite typed failures.
- One VFS batch is bounded at 4,096 events and each path at 4,096 characters/8,192 UTF-8 bytes.
  Classify it purely, then advance the local counter once. Do not refresh, import, walk, hash,
  schedule semantic work, or block.
- KVP-016 records no production epoch or freshness policy. KVP-017 owns observation and
  comparison; KVP-019 owns freshness policy.
- KVP-017 implementation files live under `epoch/`; follow its guide for strong root identities,
  typed source installation, VFS bounds, and detached epoch ownership.
- `AdmittedIdeProject.admitVfsPassiveRead` performs one retained-source observation and consumes
  its result. It returns only the detached capability or finite freshness failure; it does not
  expose Project, source, callback, listener, queue, or semantic-read authority.

## Focused proof

Run the admission, epoch-characterization, detached negative/positive, detached bytecode, and
project-read epoch selectors named by the module guide. The proof must retain recursive public
surface, exact report bytes, and exact live-adapter class fingerprints.
