---
type: Repository Guide
title: Kast repository guide
description: Find Kast architecture, semantic contracts, runtime flows, installation and focused verification through source-bound OpenWiki concepts.
tags: [kast, navigation, architecture, verification]
sources:
  - id: openwiki-source-7fa48d17b46dccf82f4b3238
    resource: repo://runtime/hosted/build.gradle.kts
  - id: openwiki-source-e620d7484b72a53c7fa812cd
    resource: repo://settings.gradle.kts
  - id: openwiki-source-096cb7b190932815f87b4a1d
    resource: repo://workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-16T21:29:39.165Z
---

# Kast repository guide

Kast gives coding agents compiler-grounded reads and controlled changes through an existing IDEA project.
The active graph separates contracts, services, native adapters, hosted composition, transport, and distribution.
Live topology dependencies support callback fact reuse inside the hosted runtime.
Historical capture, SQLite storage, and graph queries have separate owners and evidence limits.
The former isolated importer and indexer remain outside the active graph.

Read `AGENTS.md` for repository rules.
Use this wiki when a concrete architecture, dependency, or workflow question needs context.
Search for the task and read the matching complete section.
Examine consequential claims against cited source, contracts, schemas, and focused tests.

## Find an owner or mechanism

The [functional package map](architecture/functional-packages.md) assigns every active production package an explanation.
Its inventory includes the Gradle modules and included build logic.
The map also routes non-Kotlin adapters, installation helpers, release automation, and test harnesses.
Package coverage does not prove every branch or native effect.

| Question | Start with | Continue with |
| --- | --- | --- |
| Which module owns an effect? | [Architecture](modules/architecture.md) | [Build roles](development/build-and-architecture.md), [kernel](modules/kernel.md) |
| How does a public request enter? | [Public admission](queries/public-admission.md) | [Public tools](contracts/public-tools.md), [contract generation](development/contracts-and-generation.md) |
| How does composition execute? | [Query execution](queries/execution-and-composition.md) | [TRACE and completion](queries/trace-and-completion.md), [impact proof](queries/impact-and-peer-proof.md) |
| How does output continue? | [Retained output](queries/retention-and-presentation.md) | [Agent delivery](transport/harness-delivery.md), [tool RPC](transport/direct-mcp-and-tool-rpc.md) |
| Which workspace facts permit a read? | [Read authority](semantics/workspace/read-authority.md) | [Model and freshness](semantics/workspace/model-and-freshness.md), [hosted publication](runtime/hosted-composition-and-publication.md) |
| How does IDEA refresh occur? | [Lifecycle and refresh](runtime/idea-lifecycle-and-refresh.md) | [Coordinator preparation](transport/coordinator-and-workspace-preparation.md), [hosted query flow](flows/hosted-query.md) |
| How does discovery preserve identity? | [Symbol discovery](semantics/symbol/discovery-and-identity.md) | [Source anchors](semantics/source/anchors-and-paging.md), [source failures](contracts/source-failures.md) |
| Which relation facts come from the compiler? | [Named relations](semantics/relation/named-relations.md) | [Value flow](semantics/relation/value-flow-and-models.md), [callback supply](semantics/relation/callback-supply-and-binding.md) |
| What proves callback completion? | [Callback reuse and completion](semantics/relation/callback-reuse-and-completion.md) | [Live dependency proofs](topology/live-dependencies-and-reuse.md) |
| How do traversal and diagnostics stop? | [Traversal frontiers](semantics/traversal/frontiers-and-continuations.md) | [Diagnostic scans](semantics/diagnostics/scans-and-progress.md) |
| What permits a source change? | [Planning and write admission](changes/planning-and-write-admission.md) | [Receipts and recovery](changes/receipts-and-recovery.md), [change flow](flows/change-lifecycle.md) |
| What does historical storage prove? | [Capture and storage](topology/historical-capture-and-storage.md) | [Graph queries](topology/graph-queries.md), [publication contracts](flows/workspace-publication.md) |
| How do Codex clients connect? | [Hosts and framing](transport/codex-hosts-and-framing.md) | [Durable records](transport/invocations-and-durable-records.md), [configuration](contracts/configuration.md) |
| How does installation preserve ownership? | [Managed files](distribution/installation-and-managed-files.md) | [Management and connections](distribution/management-and-connections.md), [upgrades and reset](distribution/upgrades-shutdown-and-reset.md) |
| How do tested bytes reach a release? | [Candidates and promotion](distribution/release-candidates-and-promotion.md) | [Distribution overview](modules/distribution.md) |
| What does installed documentation prove? | [Knowledge projection](knowledge/installed-projection-and-retrieval.md) | [Installed knowledge contract](contracts/installed-knowledge.md) |
| Which evidence supports a change? | [Testing and native qualification](development/testing-and-native-qualification.md) | [Evidence authority](glossary/evidence-authority.md), [operation outcomes](contracts/operation-outcomes.md) |

## Evidence and upkeep

OpenWiki owns page Claims, source versions, directory indexes, and provenance through its resumable page lifecycle.
A recorded source version identifies the examined input.
Markdown and Claims do not establish compiler resolution or native runtime behavior.
Preserve qualifications and historical evidence.

`SemanticReadAuthority` distinguishes published leases from original-owner live admission.
A decoded live reference does not grant execution authority.
The retained `code_sources` extension supplies exact declaration citations for repository checks.
Kotlin PSI syntax and Python AST checks establish declaration presence without compiler or real-host proof.

After cited source changes, run `./gradlew knowledgeImpact`.
After source or page changes, run `./gradlew verifyKnowledgeBase`.
Use the OpenWiki lifecycle to reconcile affected Claims and finish each page refresh.
Keep managed Claim files, run state, indexes, and provenance under that owner.
The [knowledge upkeep guide](development/knowledge-upkeep.md) explains refresh triggers and limitations.

The [complete index](index.md) provides category navigation.
The [development guide](../docs/development.md) explains focused behavior tests and repository gates.
