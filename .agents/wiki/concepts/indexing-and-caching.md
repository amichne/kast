# Indexing and caching

Kast depends on indexing and cache persistence to make repeated queries
responsive. This page captures the pieces that turn a warm daemon into a fast
daemon.

## Summary

The backend uses background indexing and SQLite-backed caches to avoid
rediscovering every symbol from scratch. Reference search and candidate
resolution depend on those indices staying coherent with the workspace.

## What the wiki currently believes

- Background indexing is a lifecycle concern, not an isolated helper.
- SQLite persistence matters most for faster warm restarts and incremental
  startup.
- Candidate resolution narrows the search space before deeper semantic work
  happens.

## Evidence and sources

These pages define the indexing model.

- [[sources/indexing-caching-and-reference-search]] - Explains the mutable
  identifier index, cache schema, and resolver.
- [[sources/backend-standalone-analysis-engine]] - Places indexing among the
  backend's main subsystems.
- [[sources/backend-standalone-integration-tests]] - Tests indexing invariants,
  persistence, and performance baselines.
- [[sources/glossary]] - Supplies the shared vocabulary around indexing and
  caching.

## Related pages

These pages explain the work enabled by warm indices.

- [[entities/backend-standalone]]
- [[concepts/workspace-discovery-and-module-modeling]]
- [[concepts/semantic-analysis-operations]]
- [[analyses/safety-and-correctness-story]]

## Open questions

The current source set does not quantify a few important tradeoffs.

- Which cache invalidation paths are most expensive in large workspaces?
- How much warm-start improvement comes from SQLite persistence versus in-memory
  reuse within a single daemon run?
