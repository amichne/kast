# Session Lifecycle and Analysis Operations

This page summarizes the raw note [[Session-Lifecycle-and-Analysis-Operations]].
It is the most direct source for how the backend executes semantic work.

## Source

This source is a backend execution note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Session-Lifecycle-and-Analysis-Operations]]

## Summary

This note explains `StandaloneAnalysisSession`, backend locking, refresh versus
rebuild behavior, the query-to-result data flow, and Java compatibility support.
Its main contribution is showing how semantic operations are grounded in a
managed session lifecycle rather than a stateless API call.

It also clarifies that correctness and concurrency are intertwined because the
backend is long-lived and shared.

## Key claims

- Session ownership and locking are central to correct backend behavior.
- Refresh and rebuild are distinct lifecycle actions with different costs.
- Semantic operations depend on stable session state and compatibility shims.

## Connections

This source sharpens the operational concept pages.

- Reinforces [[entities/backend-standalone]]
- Reinforces [[concepts/semantic-analysis-operations]]
- Supports [[analyses/end-to-end-request-lifecycle]]

## Open questions

This source leaves some operator-facing detail implicit.

- Which events force a rebuild instead of a refresh?
- How visible are session transitions to CLI callers and agents?

## Pages updated from this source

The pages below now reflect this source.

- [[entities/backend-standalone]]
- [[concepts/semantic-analysis-operations]]
- [[analyses/end-to-end-request-lifecycle]]
