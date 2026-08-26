# Existing-Project admission source guide

This package owns the one transition from a live IntelliJ `Project` to `AdmittedIdeProject`.

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

## Focused proof

Run the negative and positive selectors named by the module guide. The positive suite must retain
the JVM-surface check for raw `Project` exposure and exact generated report bytes.
