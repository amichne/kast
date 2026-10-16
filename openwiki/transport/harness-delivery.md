---
type: System Explanation
title: Agent context and harness delivery
description: Explains shared session context, plugin hooks, Pi native MCP, and the optional RPC adapters with bounded output delivery.
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
  - path: agent-tools/hooks/session_start.py
  - path: agent-tools/onboarding.template.md
  - path: pi/mcp-extension.ts
  - path: cli/build.gradle.kts
  - path: packaging/generate-public-query.py
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/copilot/CopilotFixtureProjection.kt
  - path: cli/src/test/js/harness-adapters.test.mjs
  - path: cli/src/test/js/query-delivery-contract.test.mjs
  - path: cli/src/main/js/query-presentation.mjs
  - path: cli/src/test/js/query-presentation.test.mjs
sources:
  - id: openwiki-source-dd9e88cd86195114839ba128
    resource: repo://agent-tools/hooks/copilot.json
  - id: openwiki-source-0ef9393c85b75ed872cfaa50
    resource: repo://agent-tools/hooks/hooks.json
  - id: openwiki-source-11016f6b7875699873f6eff3
    resource: repo://agent-tools/hooks/session_start.py
  - id: openwiki-source-0f47b5cd95dd664d8cb6e398
    resource: repo://agent-tools/onboarding.template.md
  - id: openwiki-source-19c1ebaf58581a8fc0c5c10a
    resource: repo://cli/build.gradle.kts
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
  - id: openwiki-source-c3a707e4531bdd548867dd23
    resource: repo://packaging/generate-public-query.py
  - id: openwiki-source-32e1c94e6e0a0ff71b06b0e3
    resource: repo://pi/extension.ts
  - id: openwiki-source-6b18658d32b10e2368e7dc20
    resource: repo://pi/mcp-extension.ts
generated: { by: "codex", at: "2026-10-16T07:07:29.171Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-16T07:07:29.171Z
---

# Agent context and harness delivery

## One brief supplies session context

The Codex and Copilot plugins select host-specific session hooks.
Their shared script reads the same brief and emits the envelope that each host accepts.
It adds the exact session workspace and absolute paths to the query schema and named example array.
The hook invokes Python 3.9 or later and uses only the standard library.
It starts no uv process and downloads no dependencies.

Invalid events, absent resources, linked resources, and oversized context reject without partial context.
Codex owns hook trust. Plugin installation alone does not prove context delivery.

`onboarding.template.md` owns the shared prose.
The public-query generator derives enum lists, the schema, and the example array from `tools.schema.json`.
The brief explains that interfaces use the `CLASS` declaration family.
It distinguishes directory paths, Kotlin packages, exact source-set names, output fields, and issued references.
The plugin does not publish a discoverable skill.

The installed Pi extension registers Kast through native `registerMcpServer`.
It reads bounded local resources directly with Node and adds the shared brief before each model request.
An absent or invalid resource produces one warning while normal turns and native MCP stay available.
Pi starts no Python or uv process for context.
Pi owns MCP discovery, permissions, and connection lifetime.
The CLI distribution selects `pi/mcp-extension.ts` for `kast connect pi`.

## Optional RPC adapters consume one catalog

The optional Copilot and Pi RPC extensions discover the shared tool RPC catalog.
They admit its contract version, timeout, response limit, tool list, and query-session capability before registration.
Each adapter runs the admitted command in the selected workspace with explicit process ownership.
Catalog mismatch remains a startup failure with the observed version and executable.

Copilot supplies the catalog tools through its extension session.
Pi registers matching tools through its extension API and preserves the generated loading policy.
The source adapter at `pi/extension.ts` keeps the optional RPC implementation.

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
- [native-onboarding.test.mjs](../../cli/src/test/js/native-onboarding.test.mjs)
- [test-agent-onboarding.py](../../packaging/test-agent-onboarding.py)

## Source owners

- [query-delivery.mjs](../../cli/src/main/js/query-delivery.mjs)
- [query-delivery-contract.mjs](../../cli/src/main/js/query-delivery-contract.mjs)
- [query-session.mjs](../../cli/src/main/js/query-session.mjs)
- [query-delivery.schema.json](../../cli/src/main/resources/query-delivery.schema.json)
- [extension.mjs](../../copilot/extension.mjs)
- [extension.ts](../../pi/extension.ts)
- [session_start.py](../../agent-tools/hooks/session_start.py)
- [onboarding.template.md](../../agent-tools/onboarding.template.md)
- [mcp-extension.ts](../../pi/mcp-extension.ts)
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
