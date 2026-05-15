# analysis-api: Shared Contract Layer

This page summarizes the raw note [[analysis-api-Shared-Contract-Layer]]. It is
the authoritative source for the shared backend contract in the current corpus.

## Source

This source is a contract-layer note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[analysis-api-Shared-Contract-Layer]]

## Summary

This note explains the `AnalysisBackend` interface, the major request and
result types, capability reporting, and the file-operation models used by
mutation features. Its main contribution is defining what Kast means by a
semantic backend.

It also makes clear that the contract is a stable shared language between the
CLI, transport, and concrete backend implementations.

## Key claims

- `analysis-api` defines the canonical semantic operation surface.
- Capabilities and runtime status are explicit data, not hidden assumptions.
- Mutation flows depend on shared edit and file-operation models.

## Connections

This source anchors the protocol-focused pages.

- Reinforces [[entities/analysis-api]]
- Reinforces [[concepts/semantic-analysis-operations]]
- Supports [[analyses/safety-and-correctness-story]]

## Open questions

This source is strong on types and weaker on policy.

- Which fields are required for long-term compatibility guarantees?
- Which capabilities are optional in plausible future backends?

## Pages updated from this source

The pages below were updated from this source.

- [[entities/analysis-api]]
- [[concepts/semantic-analysis-operations]]
- [[analyses/safety-and-correctness-story]]
