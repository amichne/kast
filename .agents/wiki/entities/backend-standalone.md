# backend-standalone

`backend-standalone` is the core Kast analysis engine. It wraps the Kotlin K2
Analysis API in a long-lived daemon that owns workspace discovery, analysis
sessions, indexing, telemetry, and semantic operations.

## Summary

This backend is the durable center of the system. It keeps compiler state and
workspace knowledge resident so repeated semantic queries can stay fast without
requiring a full IDE.

## What the wiki currently believes

- The backend is stateful by design because session reuse is the main
  performance strategy.
- Workspace discovery, indexing, and traversal are first-class backend concerns,
  not thin utilities.
- Observability and testing are built into the backend because correctness and
  trust matter more when the process is long-lived and mutation-capable.

## Evidence and sources

The sources below shape the current view of the standalone backend.

- [[sources/backend-standalone-analysis-engine]] - Summarizes the engine and its
  major subsystems.
- [[sources/session-lifecycle-and-analysis-operations]] - Explains session
  ownership, locking, refresh, and operations.
- [[sources/workspace-discovery-and-module-modeling]] - Covers workspace
  discovery and module graphs.
- [[sources/indexing-caching-and-reference-search]] - Covers background
  indexing, caches, and candidate resolution.
- [[sources/telemetry-and-observability]] - Covers debug instrumentation and
  export.

## Related pages

The pages below break the backend into durable concepts.

- [[concepts/semantic-analysis-operations]]
- [[concepts/workspace-discovery-and-module-modeling]]
- [[concepts/indexing-and-caching]]
- [[concepts/hierarchy-traversal]]
- [[concepts/telemetry-and-observability]]
- [[analyses/end-to-end-request-lifecycle]]

## Open questions

The current corpus leaves a few backend topics underspecified.

- Which workloads dominate warm-path latency in large multi-module workspaces?
- Which backend features depend most heavily on IntelliJ compatibility shims?
