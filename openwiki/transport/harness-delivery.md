---
type: System Explanation
title: Agent context and skill delivery
description: Explains the exported Kast skill, the passive session context hook, and the dotagents-declared MCP entry; Kast writes no agent harness configuration.
tags:
  - kast
  - transport
code_sources:
  - path: agent-tools/skills/kast/SKILL.md
  - path: agent-tools/skills/kast/public-cli.md
  - path: agent-tools/README.md
  - path: agent-tools/hooks/hooks.json
  - path: agent-tools/mcp.json
  - path: agent-tools/onboarding.template.md
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - path: packaging/internal/query/generate-public-query.py
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHook.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookPreparation.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookManagedPreparationTest.kt
  - path: docs/public/reference/integrations.mdx
sources:
  - id: openwiki-source-0ef9393c85b75ed872cfaa50
    resource: repo://agent-tools/hooks/hooks.json
  - id: openwiki-source-0f47b5cd95dd664d8cb6e398
    resource: repo://agent-tools/onboarding.template.md
  - id: openwiki-source-3ea243d276faf488de77c033
    resource: repo://agent-tools/README.md
  - id: openwiki-source-20e85ee434d9dedec13e85f7
    resource: repo://build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - id: openwiki-source-a387d9d545afff426d34a10a
    resource: repo://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - id: openwiki-source-e2eb0f864f441066834169f4
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/ManagedToolInvocation.kt
  - id: openwiki-source-e1bc625b6dcbcb48924bf9b4
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHook.kt
  - id: openwiki-source-80668d86bee80c4df5797fa0
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookPreparation.kt
  - id: openwiki-source-ed6011399144254764b19542
    resource: repo://cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookManagedPreparationTest.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-1c6c64417570ecf53e6c68a2
    resource: repo://docs/public/reference/integrations.mdx
  - id: openwiki-source-ef31c8fc167ea83cd99b28bc
    resource: repo://packaging/internal/query/generate-public-query.py
generated: { by: "codex", at: "2026-11-21T22:16:17.145Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-22T20:53:36.158Z
---

# Agent context and skill delivery

## Kast writes no agent configuration

The canonical `agent-tools/skills/kast` directory supplies native CLI guidance and its linked CLI reference.
`kast install skill` exports that complete directory, by default to `./.agents/skills/kast`.
An identical export returns `ALREADY_INSTALLED` without writes; a different or incomplete directory returns `SKILL_EXISTS` and stays unchanged.
Any other `install` target rejects and directs the caller to dotagents.

An agent receives Kast through the repository's `agents.toml`, which dotagents applies.
That file declares the exported skill and one MCP server whose command runs `kast --internal-entrypoint=mcp`.
Kast does not register providers, write plugin caches, or refresh harness guidance.
Harness-specific installation, the Copilot and Pi extensions, and the tool RPC adapters are removed.
Start a fresh agent session after setup so the agent loads the skill and MCP server.

Control assembly projects the canonical skill into a versioned agent plugin under `share/kast/agent-tools`.
`GenerateAgentToolsTask` stamps the product version into the skill metadata and requires a semantic version.
The default plugin carries no MCP transport entry; distribution verification rejects one and admits only the declared hook file.

## Session context stays passive

`hooks.json` declares one session-start hook that runs `kast --internal-entrypoint=query hook session-start`.
The hook discovers the canonical workspace root and returns context text only.
It grants no trust, creates no workspace state, and starts no Host.
Kast defers workspace preparation to the first explicit semantic query.

Explicit internal `workspace wait` is a separate path.
It enters shared managed preparation with installed-product checks, inherited-state rejection, and a request-owned lease.
Typed preparation failures remain distinct from semantic output.

## Generated guidance

`onboarding.template.md` owns the onboarding prose.
The public-query generator derives enum lists, the query schema, and the example array in `agent-tools/references` from the authored `tools.schema.json`.
`verifyPublicQueryGeneration` rejects a stale generated reference.
Generated guidance does not establish that a live agent loaded it or invoked a tool.

## Focused tests

- [PublicHookManagedPreparationTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookManagedPreparationTest.kt)

## Source owners

- [SKILL.md](../../agent-tools/skills/kast/SKILL.md)
- [README.md](../../agent-tools/README.md)
- [hooks.json](../../agent-tools/hooks/hooks.json)
- [onboarding.template.md](../../agent-tools/onboarding.template.md)
- [GenerateAgentToolsTask.kt](../../build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt)
- [generate-public-query.py](../../packaging/internal/query/generate-public-query.py)
- [SkillExport.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt)
- [PublicHook.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHook.kt)
- [Agent setup guide](../../docs/public/reference/integrations.mdx)

## Read next

- [Direct MCP](direct-mcp-and-tool-rpc.md)
- [Native management and agent connections](../distribution/management-and-connections.md)
- [Retained output](../queries/retention-and-presentation.md)
