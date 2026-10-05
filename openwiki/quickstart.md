---
type: Repository Guide
title: Kast repository guide
description: Find Kast architecture, semantic contracts, runtime flows, installation and focused verification through source-bound OpenWiki concepts.
tags: [kast, navigation, architecture, verification]
sources:
  - id: openwiki-source-e620d7484b72a53c7fa812cd
    resource: repo://settings.gradle.kts
  - id: openwiki-source-096cb7b190932815f87b4a1d
    resource: repo://workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
generated: { by: "codex", at: "2026-10-02T02:00:02.467Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-05T19:11:53.820Z
---

# Kast repository guide

Kast gives coding agents compiler-grounded reads and controlled changes through an existing IDEA project. The active Gradle graph contains domain contracts, services, native adapters, the hosted runtime, App Server, CLI and distribution modules. Retained topology modules remain a separate buildable system; the former isolated importer and indexer are outside the active graph.

Read the governing `AGENTS.md` for engineering rules. Use OpenWiki when a concrete architecture or dependency question needs context. Search by the task, then read the relevant returned page section. Stop when the question is grounded and verify consequential details against the cited source, tests and schemas.

## Choose the question

| Work | Start with | Follow when needed |
| --- | --- | --- |
| Module ownership and effects | [Architecture](modules/architecture.md) | [Kernel](modules/kernel.md), [workspace](modules/workspace.md) |
| Agent request and output contract | [Public tools](contracts/public-tools.md) | [Protocol](modules/protocol.md), [registry](contracts/operation-registry.md) |
| Existing IDEA read and freshness | [Hosted query](flows/hosted-query.md) | [Request dispatch](flows/request-dispatch.md), [runtime hosts](modules/runtime-hosts.md) |
| Query composition and continuation | [Semantic query](flows/semantic-query.md) | [Query protocol](modules/query-protocol.md), [semantic read domains](modules/semantic-reads.md) |
| Source selection or rejection | [Source identity](contracts/source-identity.md) | [Source failures](contracts/source-failures.md), [compiler identity](glossary/compiler-identity.md) |
| Source mutation or recovery | [Change lifecycle](flows/change-lifecycle.md) | [Semantic change](modules/change.md), [durable evidence](modules/topology-evidence.md) |
| Install, connection or release | [Distribution](modules/distribution.md) | [Configuration](contracts/configuration.md), [installed knowledge](contracts/installed-knowledge.md) |
| Proof and failure vocabulary | [Evidence authority](glossary/evidence-authority.md) | [Outcomes](contracts/operation-outcomes.md), [refinement](glossary/refinement.md) |
| Historical publication contracts | [Workspace publication](flows/workspace-publication.md) | [Evidence generation](glossary/evidence-generation.md) |

## Evidence and upkeep

OpenWiki owns page Claims, source versions, indexes and provenance through its resumable page lifecycle. A source version records what was checked; Markdown and Claims do not establish compiler resolution or native runtime behavior. Preserve qualifications and historical evidence. The production `SemanticReadAuthority` explicitly distinguishes published leases from original-owner live IDE admission; a decoded live reference does not grant execution authority.

The migrated concepts preserve the repository's `code_sources` extension. Exact Kotlin declaration checks use PSI syntax; Python declaration checks use the AST. Declaration presence, compiler identity and observed native behavior are distinct evidence levels.

Use `./gradlew knowledgeImpact` after cited source changes and `./gradlew verifyKnowledgeBase` after page or source changes. Refresh affected pages through the OpenWiki skill: begin, plan, consume one assigned page at a time, submit sparse Claim decisions, then finish. Do not edit managed Claims, run metadata, indexes or provenance directly.

The [complete index](index.md) provides category navigation. The [development guide](../docs/development.md) explains focused behavior testing and the required repository gates.
