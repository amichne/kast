# Workspace Discovery and Module Modeling

This page summarizes the raw note [[Workspace-Discovery-and-Module-Modeling]].
It is the direct source for how Kast builds analyzable workspace models.

## Source

This source is a workspace-modeling note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Workspace-Discovery-and-Module-Modeling]]

## Summary

This note explains explicit roots, static Gradle discovery, Tooling API
discovery, standalone fallback, phased enrichment, module graphs, and dependency
resolution. Its main contribution is showing how raw directories become the
module and classpath structures the backend needs.

It also reveals Kast's consistent tradeoff: prefer fast enough information
first, then deepen precision when richer build metadata is available.

## Key claims

- Workspace discovery is phased to balance speed and precision.
- Gradle-aware discovery is Kast's primary path, with fallback logic available.
- Module modeling is a prerequisite for semantic correctness.

## Connections

This source feeds the discovery and performance pages.

- Reinforces [[concepts/workspace-discovery-and-module-modeling]]
- Adds detail to [[entities/backend-standalone]]
- Supports [[analyses/operator-journeys]]

## Open questions

This source leaves a few ecosystem questions open.

- Which non-Gradle layouts matter most for future improvement?
- How often does static discovery differ materially from Tooling API discovery?

## Pages updated from this source

The pages below now depend on this source.

- [[concepts/workspace-discovery-and-module-modeling]]
- [[entities/backend-standalone]]
- [[analyses/operator-journeys]]
