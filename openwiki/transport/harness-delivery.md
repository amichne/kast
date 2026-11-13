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
  - id: openwiki-source-0f47b5cd95dd664d8cb6e398
    resource: repo://agent-tools/onboarding.template.md
  - id: openwiki-source-20e85ee434d9dedec13e85f7
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - id: openwiki-source-784aad5c07345736dc67037f
    resource: repo://cli/src/main/js/query-delivery-contract.mjs
  - id: openwiki-source-82cf2cb34e9f56bad15bc34f
    resource: repo://cli/src/main/js/query-delivery.mjs
  - id: openwiki-source-777e160a643777b302b4cc98
    resource: repo://cli/src/main/js/query-presentation.mjs
  - id: openwiki-source-e2eb0f864f441066834169f4
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/ManagedToolInvocation.kt
  - id: openwiki-source-e1bc625b6dcbcb48924bf9b4
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHook.kt
  - id: openwiki-source-80668d86bee80c4df5797fa0
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookPreparation.kt
  - id: openwiki-source-268c7ef978bdd77cf949ddc4
    resource: repo://cli/src/main/resources/query-delivery.schema.json
  - id: openwiki-source-ed6011399144254764b19542
    resource: repo://cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookManagedPreparationTest.kt
  - id: openwiki-source-ffafcc2936c3877f4b78db93
    resource: repo://copilot/extension.mjs
  - id: openwiki-source-fe95fd24c4ce348de98bb357
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/HarnessUninstall.kt
  - id: openwiki-source-4ea7092ecf62f475b11026f1
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstalledGuidanceRefresh.kt
  - id: openwiki-source-cfc838a1a4168e12e43d463d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/IntegrationDisconnection.kt
  - id: openwiki-source-a62e12ff14ead337deede61b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
  - id: openwiki-source-7391bd7fab188bde9fd1c847
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ProviderRegistration.kt
  - id: openwiki-source-c3a707e4531bdd548867dd23
    resource: repo://packaging/generate-public-query.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
  - id: openwiki-source-7f5792fd36f508c6577b3562
    resource: repo://packaging/managed_product_installation.py
  - id: openwiki-source-32e1c94e6e0a0ff71b06b0e3
    resource: repo://pi/extension.ts
  - id: openwiki-source-6b18658d32b10e2368e7dc20
    resource: repo://pi/mcp-extension.ts
generated: { by: "amp", at: "2026-11-13T00:41:13.770Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-13T03:20:11.225Z
---

# Agent context and harness delivery

## CLI-first guidance is the default

The canonical `skills/kast` directory supplies native CLI guidance and its local reference documents.
Codex and Copilot plugins expose that skill through their native plugin discovery.
Pi uses its native skill directory.
`kast install <harness>` admits only the selected manifest-owned installed bundle.
The default generated composition includes passive session hooks without automatic MCP registration or Python scripts.

`--mcp` selects the complete optional composition and prepares its owned Python environment explicitly.
The Pi registration invokes the installed private interpreter; it does not require a user `uv` command.
CLI and MCP use the same managed workspace admission, private Host, and semantic contracts.
A provider cache must match selected resource bytes. Equal versions alone do not establish that match.
Control assembly stamps the skill front matter with the Control version.
Legacy and managed installer completion refresh owned harness guidance through the same capability-marked branch.
`ProductInstalled` carries notices and enters that branch after activation or recovery.
Failed or interrupted refresh preserves `HARNESS_REFRESH_REQUIRED`; success clears that notice without discarding other notices.
Source-level refresh integration does not establish that a live provider loaded the new guidance.
A standalone export copies the complete canonical skill directory without provider configuration.
After setup or a core update, start a fresh agent session and obey the result's harness-refresh notice.

`onboarding.template.md` owns the optional MCP brief.
The public-query generator derives enum lists, the schema, and the example array from `tools.schema.json`.
The generated compositions select native passive session hooks.
Legacy Python hook resources remain source material outside that default composition.

The optional Pi extension registers Kast through native `registerMcpServer`.
Its session-start event invokes `kast --internal-entrypoint=query hook session-start` asynchronously for the exact workspace.
This path returns context without granting trust, creating workspace state, or starting a Host.
Legacy hook wait options have no preparation effect.
Explicit internal `workspace wait` enters shared managed preparation with installed-product checks and a request-owned lease.
Inherited managed-state overrides reject there; the caller cannot prepare a Host without a lease through the old direct lifecycle path.
Pi includes the returned context before each model request and ignores replies from an older session.
Context rejection emits a bounded warning, not a readiness result.
It reads bounded local context directly with Node and adds that brief before model requests.
A rejected resource produces one warning while later turns and native MCP remain available.
Its context path starts no Python or uv process. The explicitly selected MCP server uses its separate transport process and private interpreter.
Pi owns MCP discovery, permissions, and connection lifetime.

These are source-level contracts. Generated hook fixtures do not establish live provider behavior or managed native qualification.
Full product removal now invokes receipted Pi guidance cleanup through `IntegrationDisconnection`, preserving foreign siblings.
The management receipt records CODEX/COPILOT routing intent before provider mutation; provider inventories still establish resource ownership.
Ordinary uninstall removes recorded provider resources before Host retirement, and conflicts preserve the Host.
The registration lock protects a final empty-record check through payload removal.
Explicit harness removal forgets only the selected routing record after verified cleanup.
These integrated source paths do not establish native provider cleanup or product qualification.

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
- [`mcp-extension.ts`](../../pi/mcp-extension.ts)
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
