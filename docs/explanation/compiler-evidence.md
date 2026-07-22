---
type: Explanation
title: Compiler-Backed Evidence
description: Why Kast models Kotlin symbols, relations, coverage, and failures as typed evidence rather than text matches.
tags: [compiler, kotlin, semantic-graph, evidence, coverage]
code_sources:
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt
  - path: cli-rs/src/agent/graphify.rs
  - path: cli-rs/src/runtime/workspace_admission.rs
---

# Compiler-Backed Evidence

Kotlin source text contains names, but many development questions depend on
facts that only a compiler model can establish: which overload a call reaches,
which declaration owns a member, whether a type implements another type, and
whether all relevant source modules were analyzed.

Kast treats those facts as evidence with identity, provenance, and limits.

## Symbols have canonical identity

A semantic graph symbol carries a compiler-derived canonical key, declaration
kind, name, optional fully qualified name and callable signature, nearest
owner, repository-relative Kotlin path, byte range, and line. The key is
overload-safe; the displayed name alone is not the identity.

That distinction is why an ambiguous lookup returns candidates instead of
choosing the first matching string.

## Relations identify both ends and the occurrence

The graph represents containment, methods, enum cases, inheritance,
implementation, calls, and references. A relation records source and target
keys plus the Kotlin path, byte range, line, and source context such as field,
parameter type, return type, generic argument, or call.

External library and JDK targets may be intentionally omitted from a workspace
graph. The coverage record counts those omissions rather than inventing local
nodes for them.

## Coverage belongs to the result

Each requested file is marked refreshed, cached, or removed and is bound to a
content hash. Compiler diagnostics are attached to the same file coverage.
Paged results share a source-index generation and scope fingerprint, which
prevents unrelated snapshots from looking like one complete graph.

Relationship operations use the same principle. Complete, limited, and
resumable outcomes are different evidence states. An empty limited result is
not proof that no relationship exists.

## Graph projection preserves compiler provenance

The compiler-backed Graphify projection accepts only absolute Kotlin paths
contained by the exact workspace. It requests semantic pages from the active
backend, maps canonical keys to stable public node IDs, retains relation
context and resolved targets, and writes the fragment atomically.

Incremental extraction requires a compatible base graph. Kotlin deletions
require a full rebuild. Those constraints keep one graph from quietly mixing
incompatible symbol identities.

## Failures remain explicit

Workspace admission reports `COMPILER_BACKED` only after the selected runtime
matches the requested root and backend. Indexing, unavailable source modules,
an unprepared workspace, ambiguous backend selection, and an unavailable
reference index remain typed limitations. Kast fails closed when those facts
would make a semantic claim unsafe.
