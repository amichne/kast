---
type: API Contract
title: Source and workspace identity
description: Semantic reads carry canonical workspace generation, exact selectors, coordinates, and source snapshots so later stages retain what earlier stages proved.
resource: file://source/contract
tags: [source, identity, workspace, symbol]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [CanonicalWorkspaceRoot, SemanticReadLease]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
    symbols: [ExactDeclarationSelector]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt
    symbols: [SourceSnapshot]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt
    symbols: [Utf16Coordinate]
---

# Source and workspace identity

A source read is not identified by path text alone. The contract combines:

- a physically canonical workspace root;
- a published evidence generation;
- an exact compiler-grounded symbol or source selector;
- admitted UTF-16 coordinates and ranges; and
- a snapshot/content identity where source text matters.

Later stages consume these stronger values. If the workspace generation moves, the lease guard refuses the effect instead of silently reusing stale evidence.

See [compiler identity](../glossary/compiler-identity.md) and [workspace publication](../flows/workspace-publication.md).
