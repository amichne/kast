# Telemetry and Observability

This page summarizes the raw note [[Telemetry-and-Observability]]. It is the
main source for Kast's local observability story.

## Source

This source is an observability note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Telemetry-and-Observability]]

## Summary

This note explains telemetry configuration, scopes, detail levels, span
instrumentation, and JSONL export. Its main contribution is showing that the
standalone backend can be inspected through plain local files without depending
on an external collector.

It also shows that observability is scoped and opt-in, which limits overhead and
keeps the common path simple.

## Key claims

- Telemetry is configured through environment variables and scope settings.
- The standalone backend exports spans as JSON Lines to local storage.
- Instrumentation is designed around selective inspection rather than permanent
  always-on tracing.

## Connections

This source informs the runtime observability pages.

- Reinforces [[concepts/telemetry-and-observability]]
- Adds detail to [[entities/backend-standalone]]
- Supports [[analyses/safety-and-correctness-story]]

## Open questions

This source is clear on mechanism and lighter on practice.

- Which scopes are most helpful for diagnosing slow refactors?
- When does verbose detail become too expensive in large workspaces?

## Pages updated from this source

The pages below now depend on this source.

- [[concepts/telemetry-and-observability]]
- [[entities/backend-standalone]]
- [[analyses/safety-and-correctness-story]]
