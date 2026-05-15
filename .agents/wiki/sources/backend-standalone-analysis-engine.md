# Backend-Standalone: Analysis Engine

This page summarizes the raw note [[Backend-Standalone-Analysis-Engine]]. It is
the broadest source for the resident K2-backed engine.

## Source

This source is a backend architecture note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Backend-Standalone-Analysis-Engine]]

## Summary

This note describes the standalone backend as the core intelligence of Kast. It
covers session lifecycle, workspace discovery, indexing, traversal, telemetry,
and the request path from query input to PSI and compiler state.

Its main contribution is showing that the backend is a coordinated subsystem
rather than a thin wrapper around the Kotlin API.

## Key claims

- `backend-standalone` is the semantic center of Kast.
- Session, discovery, indexing, traversal, and telemetry are co-equal backend
  subsystems.
- The backend is stateful because performance depends on reuse.

## Connections

This source informs nearly every backend-focused page.

- Reinforces [[entities/backend-standalone]]
- Reinforces [[concepts/workspace-discovery-and-module-modeling]]
- Reinforces [[concepts/indexing-and-caching]]

## Open questions

This source is strong on structure and lighter on empirical metrics.

- Which subsystem dominates warm-path latency in large workspaces?
- Which backend responsibilities are most likely to change with Kotlin API
  shifts?

## Pages updated from this source

The pages below now depend on this source.

- [[entities/backend-standalone]]
- [[concepts/workspace-discovery-and-module-modeling]]
- [[concepts/indexing-and-caching]]
