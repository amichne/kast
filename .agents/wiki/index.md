# Kast index

This index is the entry point for the compiled Kast wiki. Use it to move from
high-level questions to the source summaries, concept pages, and cross-source
analyses that now sit alongside the raw `Kast/*.md` notes.

## Overview

- [[overview]] - Scope, source layout, and the major themes that organize this
  wiki.

## Entities

- [[entities/kast-cli]] - The native front end that resolves an installation,
  manages daemon lifecycles, and exposes the user-facing commands.
- [[entities/analysis-api]] - The stable protocol and data model shared by the
  CLI, transport layer, and analysis backend.
- [[entities/analysis-server]] - The JSON-RPC server layer that hosts backend
  instances and exposes them over local transports.
- [[entities/backend-standalone]] - The resident Kotlin K2 analysis engine that
  owns sessions, workspace models, indices, and semantic queries.

## Concepts

- [[concepts/client-daemon-architecture]] - Why Kast splits a lightweight CLI
  from a long-lived JVM daemon.
- [[concepts/semantic-analysis-operations]] - The read and mutation operations
  that the backend contract guarantees.
- [[concepts/workspace-discovery-and-module-modeling]] - How Kast turns a
  directory into analyzable modules and source sets.
- [[concepts/indexing-and-caching]] - How background indexing and SQLite caches
  keep repeat queries fast.
- [[concepts/hierarchy-traversal]] - How Kast computes call and type hierarchies
  from natural-language prompts and code coordinates.
- [[concepts/installation-and-instance-management]] - How installation,
  instance selection, and the discovery cascade work.
- [[concepts/telemetry-and-observability]] - How the standalone backend emits
  local telemetry for debugging and performance work.
- [[concepts/testing-and-verification]] - How contract tests, integration
  tests, smoke tests, and CI preserve behavior.
- [[concepts/llm-agent-workflows]] - How Kast packages scripts and conventions
  for agent-driven semantic code intelligence.

## Analyses

- [[analyses/end-to-end-request-lifecycle]] - The path from a CLI or agent
  request to a semantic result or applied edit.
- [[analyses/operator-journeys]] - The main human and agent journeys: install,
  discover, analyze, mutate, and stop.
- [[analyses/safety-and-correctness-story]] - The layered safeguards that keep
  daemonized mutation and analysis trustworthy.

## Sources

- [[sources/readme]] - Summarizes the original corpus index and points to the
  pre-wiki note layout.
- [[sources/kast-overview]] - Establishes Kast's value proposition, major
  modules, and client-daemon split.
- [[sources/getting-started]] - Describes prerequisites, install paths, the
  discovery cascade, and the first-run lifecycle.
- [[sources/cli-command-reference]] - Catalogs commands, JSON output shapes, and
  the main operational verbs.
- [[sources/using-kast-from-an-llm-agent]] - Explains the agent skill, wrapper
  scripts, and the natural-language-to-code "golden path."
- [[sources/architecture-and-module-structure]] - Maps the Gradle modules and
  how they cooperate.
- [[sources/analysis-api-shared-contract-layer]] - Defines the shared backend
  contract and core value types.
- [[sources/analysis-server-json-rpc-transport-layer]] - Covers transport
  setup, request dispatch, descriptors, and error handling.
- [[sources/kast-cli-native-cli-module]] - Details CLI runtime management,
  transport calls, and skill installation helpers.
- [[sources/backend-standalone-analysis-engine]] - Explains the core analysis
  engine and its major subsystems.
- [[sources/session-lifecycle-and-analysis-operations]] - Focuses on session
  ownership, locking, refresh behavior, and key semantic operations.
- [[sources/workspace-discovery-and-module-modeling]] - Explains phased Gradle
  and fallback discovery.
- [[sources/indexing-caching-and-reference-search]] - Documents background
  indexing, cache persistence, and candidate resolution.
- [[sources/call-hierarchy-and-type-hierarchy-traversal]] - Explains traversal
  logic for call and type hierarchies.
- [[sources/telemetry-and-observability]] - Documents the standalone telemetry
  model and JSONL export path.
- [[sources/build-system-and-distribution]] - Covers artifact assembly,
  packaging, versioning, and release flow.
- [[sources/build-logic-and-gradle-conventions]] - Explains custom Gradle
  conventions and distribution helpers.
- [[sources/ci-cd-pipelines-and-smoke-testing]] - Covers CI workflows, smoke
  scripts, and validation hooks.
- [[sources/installation-and-instance-management]] - Describes shell installers,
  side-by-side versions, and completion support.
- [[sources/testing-infrastructure]] - Summarizes contract, integration, and
  performance testing.
- [[sources/shared-testing-fixtures-and-contract-tests]] - Explains the shared
  fixture and fake backend used to pin contract behavior.
- [[sources/backend-standalone-integration-tests]] - Covers invariants and
  full-stack backend tests.
- [[sources/glossary]] - Defines the vocabulary that keeps the other pages
  aligned.
