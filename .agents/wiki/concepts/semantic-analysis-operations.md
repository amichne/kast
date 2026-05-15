# Semantic analysis operations

Kast's core product surface is a set of semantic read and mutation operations.
Those operations are defined in the shared contract and surfaced through the
CLI, agent wrappers, and backend implementation.

## Summary

The important distinction is between reads and mutations. Reads include symbol
resolution, references, hierarchies, and diagnostics. Mutations include rename
planning and edit application, which means safety checks matter as much as raw
capability.

## What the wiki currently believes

- `resolve`, `references`, `diagnostics`, and hierarchy traversal are the main
  semantic read primitives.
- `rename` and `edits apply` turn Kast from a query engine into a refactoring
  substrate.
- Capability reporting exists so clients can know whether a backend supports a
  given operation before they rely on it.

## Evidence and sources

These pages describe the operation surface.

- [[sources/analysis-api-shared-contract-layer]] - Defines the canonical
  operations and result types.
- [[sources/cli-command-reference]] - Shows how the operations appear to users
  and agents.
- [[sources/session-lifecycle-and-analysis-operations]] - Explains how the
  standalone backend executes them.
- [[sources/call-hierarchy-and-type-hierarchy-traversal]] - Expands the
  hierarchy operations in detail.

## Related pages

These pages explain the surrounding systems those operations depend on.

- [[entities/analysis-api]]
- [[entities/backend-standalone]]
- [[concepts/hierarchy-traversal]]
- [[concepts/testing-and-verification]]
- [[analyses/end-to-end-request-lifecycle]]

## Open questions

The current sources leave some product-shaping questions open.

- Which operations are expected to remain stable for third-party automation?
- How often do mutation workflows require human review before edit application?
