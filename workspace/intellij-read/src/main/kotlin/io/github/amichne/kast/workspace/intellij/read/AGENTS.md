# Existing-Project admission source guide

This package owns the transition from a live IntelliJ `Project` to `AdmittedIdeProject` and the
state-specific transition from that capability to `DetachedIdeWorkspaceModel`.

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
- `LiveDetachedModelCapture` is the sole KVP-016 live adapter. Reject EDT entry before
  `ReadAction.computeCancellable`; inside it, recheck cancellation, disposal, open state,
  initialization, dumb mode, root, cached Gradle state, and every collection bound.
- Refine primitive observations into bounded identity and path types. Reject duplicate or
  conflicting source roots, cross-module ownership ambiguity, and duplicate classpath URLs.
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
- KVP-016 records no production epoch or freshness policy; KVP-017 owns that transition.

## Focused proof

Run the admission, epoch-characterization, detached negative/positive, and detached bytecode
selectors named by the module guide. The detached proof must retain its recursive public-surface,
exact report-byte, and exact live-adapter class-fingerprint checks.
