# Indexing, Caching, and Reference Search

This page summarizes the raw note [[Indexing,-Caching,-and-Reference-Search]].
It is the source that explains how Kast keeps repeat queries fast.

## Source

This source is an indexing note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Indexing,-Caching,-and-Reference-Search]]

## Summary

This note covers indexing phases, the mutable source identifier index, the
SQLite cache schema, incremental startup, workspace discovery caching, candidate
file resolution, and cache layout. Its main contribution is explaining the
performance substrate beneath reference search and repeated semantic work.

It also makes clear that indexing is tied to correctness as well as speed,
because stale or incomplete cache state can distort later semantic results.

## Key claims

- Background indexing and cache persistence are central performance features.
- Reference search depends on narrowing candidates before deeper analysis.
- Cache state is part of the daemon lifecycle, not a separate side channel.

## Connections

This source anchors the performance-oriented concept pages.

- Reinforces [[concepts/indexing-and-caching]]
- Adds detail to [[entities/backend-standalone]]
- Supports [[analyses/end-to-end-request-lifecycle]]

## Open questions

This source is detailed on mechanism and lighter on observed outcomes.

- Which cache invalidation cases are most expensive?
- What is the measured warm-start benefit in realistic repositories?

## Pages updated from this source

The pages below now depend on this source.

- [[concepts/indexing-and-caching]]
- [[entities/backend-standalone]]
- [[analyses/safety-and-correctness-story]]
