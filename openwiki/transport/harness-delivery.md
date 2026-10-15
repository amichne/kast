---
type: System Explanation
title: Agent harness adapters and output delivery
description: Explains Copilot and Pi adapters, generated delivery owners, bounded
  presentation, identity preservation, and the absence of implicit semantic retries.
tags:
  - kast
  - transport
code_sources:
  - path: cli/src/main/js/query-delivery.mjs
  - path: cli/src/main/js/query-delivery-contract.mjs
  - path: cli/src/main/js/query-session.mjs
  - path: cli/src/main/resources/query-delivery.schema.json
  - path: copilot/extension.mjs
  - path: pi/extension.ts
  - path: agent-tools/skills/kast/SKILL.md
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/copilot/CopilotFixtureProjection.kt
  - path: cli/src/test/js/harness-adapters.test.mjs
  - path: cli/src/test/js/query-delivery-contract.test.mjs
  - path: cli/src/main/js/query-presentation.mjs
  - path: cli/src/test/js/query-presentation.test.mjs
sources:
  - id: openwiki-source-784aad5c07345736dc67037f
    resource: repo://cli/src/main/js/query-delivery-contract.mjs
  - id: openwiki-source-82cf2cb34e9f56bad15bc34f
    resource: repo://cli/src/main/js/query-delivery.mjs
  - id: openwiki-source-777e160a643777b302b4cc98
    resource: repo://cli/src/main/js/query-presentation.mjs
  - id: openwiki-source-268c7ef978bdd77cf949ddc4
    resource: repo://cli/src/main/resources/query-delivery.schema.json
  - id: openwiki-source-ffafcc2936c3877f4b78db93
    resource: repo://copilot/extension.mjs
  - id: openwiki-source-32e1c94e6e0a0ff71b06b0e3
    resource: repo://pi/extension.ts
generated: { by: "codex", at: "2026-10-15T01:04:37.434Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T01:04:37.434Z
---

# Agent harness adapters and output delivery

## Thin adapters consume one catalog

The Copilot and Pi extensions discover the shared tool RPC catalog.
They admit its contract version, timeout, response limit, tool list, and query-session capability before registration.
Each adapter runs the admitted command in the selected workspace with explicit process ownership.
Catalog mismatch remains a startup failure with the observed version and executable.

Copilot supplies the catalog tools through its extension session.
Pi registers matching tools through its extension API and preserves the generated loading policy.
The independently installable Kast skill explains the same public query contract to agents.
It supplies guidance instead of another executor.

## Source owners generate adapter projections

`query-delivery.mjs` owns portable output accumulation.
`query-session.mjs` owns the caller-held child and framed exchange.
`query-delivery.schema.json` owns the outer delivery contract.
The generator projects these sources into each adapter.
Edit those owners before regenerated copies when behavior changes.

The RPC decoder admits canonical replies before the outer delivery layer handles them.
The delivery envelope keeps the initial reply, later pages, physical RPC accounting, byte counts, stop reason, and issued result reference.
Opaque canonical replies keep their original outcome.
`DELIVERED` describes client output delivery without a new semantic completion claim.

## Invoke once and preserve authority

Output accumulation invokes the logical query once.
Later calls use only issued `READ_RESULT` authority or output-only `RESUME` continuations.
The layer preserves result and live identities, row order, distinct occurrences, and separate evidence progress.
Repeated continuations, changed identities, malformed pages, and non-advancing output stop with explicit blockers.

The aggregate presentation has page, byte, and elapsed-time limits in addition to physical response limits.
An oversized initial reply can produce a byte-limit envelope that preserves its original outcome and issued result reference.
Lost initial replies and ambiguous transport submission do not authorize automatic semantic replay.
Cancellation terminates owned child work through the adapter's bounded cleanup path.

The JavaScript contract and adapter tests establish output and process behavior with controlled fixtures.
A successful fixture or tool catalog does not prove a real Copilot or Pi session or a fresh compiler result.

## Focused tests

- [harness-adapters.test.mjs](../../cli/src/test/js/harness-adapters.test.mjs)
- [query-delivery-contract.test.mjs](../../cli/src/test/js/query-delivery-contract.test.mjs)

## Source owners

- [query-delivery.mjs](../../cli/src/main/js/query-delivery.mjs)
- [query-delivery-contract.mjs](../../cli/src/main/js/query-delivery-contract.mjs)
- [query-session.mjs](../../cli/src/main/js/query-session.mjs)
- [query-delivery.schema.json](../../cli/src/main/resources/query-delivery.schema.json)
- [extension.mjs](../../copilot/extension.mjs)
- [extension.ts](../../pi/extension.ts)
- [SKILL.md](../../agent-tools/skills/kast/SKILL.md)
- [CopilotFixtureProjection.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/copilot/CopilotFixtureProjection.kt)

## Read next

- [Tool RPC](direct-mcp-and-tool-rpc.md)
- [Retained output](../queries/retention-and-presentation.md)
- [Native qualification](../development/testing-and-native-qualification.md)

## Summaries keep received evidence distinct

`query-presentation.mjs` starts compact text with received row, relation, and file counts, original coverage, and blockers.
Relation counts include repeated connections in received rows.
Rows without supplied file locations remain unknown.
These counts do not describe an unread suffix or replace the invocation's accumulated count.

Refinement failures keep the exact location and reason with `read_source_eligibility` set to `NOT_ISSUED`.
A discovery location cannot become an issued selector through presentation.
The summary supplies existing recovery advice without a new epoch claim, source-read request, or automatic retry.
Callback proof subjects and authority-mismatch details remain visible through their canonical variants.
