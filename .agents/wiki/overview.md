# Kast overview

This wiki treats the existing `Kast/*.md` files as immutable source notes and
adds a compiled layer on top. The compiled layer captures the durable
understanding you would want to keep after answering repeated questions about
how Kast works.

## Scope

This wiki covers Kast as a Kotlin semantic analysis system with four repeating
themes: its client-daemon architecture, its analysis operations, its
installation and runtime lifecycle, and the safeguards around correctness and
release quality.

## Raw sources and compiled pages

The top-level `Kast/*.md` notes remain the raw source layer. The synthesized
pages live in `sources/`, `entities/`, `concepts/`, and `analyses/`, so you can
trace a claim back to both a source-summary page and the underlying note.

## Major sections

The sections below organize the stable knowledge in the current Kast corpus.

- [[entities/kast-cli]] and [[entities/analysis-server]] explain the user-facing
  entry point and the local transport boundary.
- [[entities/analysis-api]] and [[entities/backend-standalone]] explain the
  semantic contract and the resident K2-based engine.
- [[concepts/client-daemon-architecture]],
  [[concepts/workspace-discovery-and-module-modeling]], and
  [[concepts/indexing-and-caching]] explain how Kast stays responsive after the
  first command.
- [[concepts/semantic-analysis-operations]],
  [[concepts/hierarchy-traversal]], and
  [[concepts/llm-agent-workflows]] explain what Kast can do once the workspace
  is live.
- [[concepts/installation-and-instance-management]],
  [[concepts/telemetry-and-observability]], and
  [[concepts/testing-and-verification]] explain how Kast is installed, observed,
  and kept trustworthy.

## Reading paths

You can move through the material based on the question you have.

- Start with [[analyses/operator-journeys]] if you want the shortest path from
  installation to the first useful command.
- Start with [[analyses/end-to-end-request-lifecycle]] if you want to trace a
  request across the whole stack.
- Start with [[analyses/safety-and-correctness-story]] if you want to understand
  why mutation and daemonized analysis are safe enough to rely on.

## Known gaps

This source set is strong on architecture, command semantics, and testing, but
it is lighter on performance numbers, Windows-specific operational details, and
examples of failures in production-sized workspaces. Those remain open areas for
future ingest.
