# Backend-Standalone Integration Tests

This page summarizes the raw note [[Backend-Standalone-Integration-Tests]]. It
is the strongest source for backend invariants under realistic conditions.

## Source

This source is an integration-testing note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Backend-Standalone-Integration-Tests]]

## Summary

This note explains backend integration tests for analysis operations, workspace
discovery, indexing, cache invariants, performance baselines, daemon
consolidation, and compatibility shims. Its main contribution is showing how
Kast validates the real backend under conditions closer to production.

It also reveals where correctness risks cluster: cache persistence, discovery,
and long-lived daemon behavior.

## Key claims

- The standalone backend is tested against realistic compiler and cache state.
- Invariants and performance baselines are part of the integration test story.
- Compatibility shims are important enough to deserve explicit coverage.

## Connections

This source strengthens the backend trust pages.

- Reinforces [[concepts/testing-and-verification]]
- Reinforces [[concepts/indexing-and-caching]]
- Supports [[analyses/safety-and-correctness-story]]

## Open questions

This source is detailed on coverage and lighter on failure history.

- Which invariant failures have been most frequent in practice?
- Which integration tests are the slowest but still most valuable?

## Pages updated from this source

The pages below now depend on this source.

- [[concepts/testing-and-verification]]
- [[concepts/indexing-and-caching]]
- [[analyses/safety-and-correctness-story]]
