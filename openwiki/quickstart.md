---
type: Repository guide
title: Kast repository guide
description: Route coding agents from Kast's repository overview to architectural ownership, runtime workflows, distribution, and focused verification. Use the topic map to locate the relevant wiki domain, then confirm behavior against source and tests.
tags: [kast, navigation, architecture, verification]
sources:
  - id: openwiki-source-8037e2358a2c4f9b2c722a11
    resource: repo://AGENTS.md
  - id: openwiki-source-23775c3de52f3ab95a13cb8b
    resource: repo://README.md
  - id: openwiki-source-7fa48d17b46dccf82f4b3238
    resource: repo://runtime/hosted/build.gradle.kts
  - id: openwiki-source-e620d7484b72a53c7fa812cd
    resource: repo://settings.gradle.kts
  - id: openwiki-source-096cb7b190932815f87b4a1d
    resource: repo://workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
generated: { by: "openwiki/0.9.0", at: "2026-11-08T22:20:55.770Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-08T22:20:55.770Z
---

# Kast repository guide

Kast gives coding agents compiler-grounded reads and controlled changes through an existing IDEA project. Its active system spans contracts, services, native adapters, hosted composition, transport, and distribution; separate owners cover historical capture, SQLite storage, and graph queries. The repository overview and contributor rules are in [README](../README.md) and [AGENTS.md](../AGENTS.md).

Use the route map below to find the architectural owner or workflow relevant to a change. Read the complete matching page section, follow its links into contracts and focused tests, and verify consequential behavior against source. A package map establishes coverage, not proof of every branch or native effect.

## Route map

| Task or question | Start here | Follow through |
| --- | --- | --- |
| Find the module or package that owns behavior | [Functional package map](architecture/functional-packages.md) | [Module architecture](modules/architecture.md), then the relevant domain page |
| Understand runtime request and composition flow | [Query execution and composition](queries/execution-and-composition.md) | [Hosted query flow](flows/hosted-query.md), [TRACE and completion](queries/trace-and-completion.md), and [retention and presentation](queries/retention-and-presentation.md) |
| Change workspace reads or IDEA refresh behavior | [Read authority](semantics/workspace/read-authority.md) | [Workspace model and freshness](semantics/workspace/model-and-freshness.md), [IDEA lifecycle and refresh](runtime/idea-lifecycle-and-refresh.md) |
| Change source mutations | [Planning and write admission](changes/planning-and-write-admission.md) | [Receipts and recovery](changes/receipts-and-recovery.md), [change lifecycle](flows/change-lifecycle.md) |
| Work on installation or packaging | [Distribution and packaging](modules/distribution.md) | [Installation and managed files](distribution/installation-and-managed-files.md), [management and connections](distribution/management-and-connections.md) |
| Promote release artifacts | [Release candidates and promotion](distribution/release-candidates-and-promotion.md) | Check artifact admission and release-pipeline policy described there |
| Select tests or qualify native behavior | [Testing and native qualification](development/testing-and-native-qualification.md) | [Evidence authority](glossary/evidence-authority.md); distinguish source-level checks from compiler and real-host evidence |
| Maintain generated knowledge | [Knowledge upkeep](development/knowledge-upkeep.md) | [Installed projection and retrieval](knowledge/installed-projection-and-retrieval.md) |

## Safe investigation

Start with the repository guidance in `AGENTS.md`; identify the owning Gradle module and contract before changing implementation. Trace callers and state owners across module boundaries rather than treating a matching symbol or package as the whole behavior. For runtime or native claims, preserve the evidence boundary: syntax and declaration checks do not establish compiler resolution or real-host behavior.

After cited source changes, run `./gradlew knowledgeImpact`; after source or wiki changes, run `./gradlew verifyKnowledgeBase`. Choose focused behavior tests and native qualification according to [testing and native qualification](development/testing-and-native-qualification.md). The [development guide](../docs/development.md) describes repository workflows.
