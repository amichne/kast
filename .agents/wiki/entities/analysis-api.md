# analysis-api

`analysis-api` is the shared contract layer for Kast. It defines the request and
response shapes, capability flags, and value types that let the CLI and backend
evolve around a common protocol.

## Summary

This module is the semantic boundary of the system. If the CLI is the control
plane, `analysis-api` is the language it uses to ask for symbol resolution,
reference search, diagnostics, hierarchy traversal, and mutation.

## What the wiki currently believes

- The backend contract is intentionally versioned and transport-agnostic.
- Read and mutation capabilities are explicit rather than implicit, which lets
  clients reason about what a backend can do.
- Higher-level features such as rename and apply-edits rely on shared models so
  the same plan can travel across module boundaries safely.

## Evidence and sources

The sources below anchor the current understanding of the contract layer.

- [[sources/analysis-api-shared-contract-layer]] - Defines `AnalysisBackend`,
  the value types, and capability enums.
- [[sources/cli-command-reference]] - Shows how those contract operations are
  exposed at the CLI boundary.
- [[sources/shared-testing-fixtures-and-contract-tests]] - Explains how the
  contract is pinned across multiple backend implementations.
- [[sources/testing-infrastructure]] - Places the shared contract inside the
  larger verification strategy.

## Related pages

The pages below expand on the contract's role in the stack.

- [[entities/kast-cli]]
- [[entities/analysis-server]]
- [[entities/backend-standalone]]
- [[concepts/semantic-analysis-operations]]
- [[analyses/safety-and-correctness-story]]

## Open questions

The current notes define the contract well, but they leave some product
questions unresolved.

- Which schema changes would force a compatibility break for external clients?
- Which operations are stable enough to support long-lived automation contracts?
