# Workspace discovery and module modeling

Before Kast can answer semantic questions, it needs a model of the workspace.
This concept page captures how Kast builds that model from explicit roots,
Gradle metadata, and fallback heuristics.

## Summary

Workspace discovery is staged to balance speed and precision. Kast starts with
cheap signals, deepens the model when Gradle information is available, and keeps
enough structure to drive source resolution, dependency lookup, and module-aware
analysis.

## What the wiki currently believes

- Explicit source roots are the fastest path when a caller already knows the
  workspace shape.
- Gradle discovery happens in phases so Kast can become useful quickly and
  improve precision later.
- Module modeling is essential because K2 analysis needs source roots,
  classpaths, and dependency edges rather than just a directory tree.

## Evidence and sources

These pages define the discovery story.

- [[sources/workspace-discovery-and-module-modeling]] - Describes discovery
  phases, Gradle heuristics, and fallback logic.
- [[sources/backend-standalone-analysis-engine]] - Places discovery among the
  backend's core subsystems.
- [[sources/session-lifecycle-and-analysis-operations]] - Shows how the session
  depends on the discovered workspace state.
- [[sources/glossary]] - Defines the shared vocabulary for workspaces, modules,
  and discovery.

## Related pages

These pages explain what Kast does once discovery succeeds.

- [[entities/backend-standalone]]
- [[concepts/indexing-and-caching]]
- [[concepts/installation-and-instance-management]]
- [[analyses/operator-journeys]]

## Open questions

The current corpus is lighter on operational edge cases.

- How often does the static Gradle path produce a model that must be corrected
  by the Tooling API?
- Which non-Gradle workspace shapes are important enough to justify richer
  fallback support?
