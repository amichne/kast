---
type: System Explanation
title: Workspace roots and read authority
description: Explains canonical roots, published leases, original-owner live admission,
  detached references, and admitted execution grants.
tags:
- kast
- semantics
code_sources:
- path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt
- path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
- path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/execution/AdmittedProjectReadExecution.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedEpochAdmission.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
- path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
- path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt
- path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
- path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedEpochAdmissionTest.kt
- path: kernel/src/test/kotlin/io/github/amichne/kast/kernel/ExecutionBudgetTest.kt
sources:
  - id: openwiki-source-02ae034b72e79a3f84b122d8
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt
  - id: openwiki-source-096cb7b190932815f87b4a1d
    resource: repo://workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
  - id: openwiki-source-66bcd153d89119a79ea0c09b
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/execution/AdmittedProjectReadExecution.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
---

# Workspace roots and read authority

## Root identity scopes every proof

A workspace operation belongs to one canonical repository or worktree root.
Root admission rejects a different or unrepresentable root before semantic work.
Two worktrees with the same Git commit still have different workspace roots.
Source and declaration identities keep that exact root through later operations.

`SemanticReadAuthority` distinguishes published workspace leases from live IDEA admission.
`requirePublished` rejects a live authority where a published generation is necessary.
The live path cannot fabricate a generation to satisfy the stronger published contract.
Historical published evidence remains interpretable without granting a current live read.

## A reference is data, not the owner

`LiveSemanticReadReference` records root, host lifetime, positive epoch revision, content view, and version.
Decoding it establishes representation validity only.
The original live owner must admit the current epoch before the reference participates in a read.
Selectors then reexamine scope, declaration identity, and content.

`LiveSemanticReadAuthority` keeps its owner privately and exposes only short admitted transitions.
Equal detached reference fields do not create another valid owner.
Environment continuity preserves a scoped comparison between admitted lifetimes without reviving the previous authority.
Retired, moved, wrong-root, wrong-host, and incomparable states remain closed failures.

## Grants preserve their admission facts

The kernel separates a caller's omitted allowance from a positive requested value.
`AdmittedExecutionBudget` keeps the request, configured default, operator ceiling, effective value, and clamp evidence for each resource.
Elapsed time also respects the remaining deadline.
Result and returned-byte grants also respect transport capacity.
Work does not gain a larger grant because output needs another page.

The hosted executor carries this admitted budget through native reads, final freshness, and publication.
`EvidenceEnvelope` binds successful payloads to their evidence basis.
`OperationOutcome` keeps complete evidence, qualified evidence, and finite rejection distinct.
Neither a decoded envelope nor an encoded success shape creates execution authority.

Authority and budget tests establish these pure and owner-scoped rules.
A live installed read still needs the actual original project owner, current model, and compiler admission.

## Focused tests

- [HostedEpochAdmissionTest.kt](../../../workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedEpochAdmissionTest.kt)
- [ExecutionBudgetTest.kt](../../../kernel/src/test/kotlin/io/github/amichne/kast/kernel/ExecutionBudgetTest.kt)

## Source owners

- [CanonicalSemanticProjectRoot.kt](../../../workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt)
- [SemanticReadAuthority.kt](../../../workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt)
- [SemanticReadLease.kt](../../../workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt)
- [AdmittedProjectReadExecution.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/execution/AdmittedProjectReadExecution.kt)
- [HostedEpochAdmission.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedEpochAdmission.kt)
- [HostedQueryExecutor.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt)
- [EvidenceEnvelope.kt](../../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt)
- [ExecutionBudget.kt](../../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt)
- [Refinement.kt](../../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt)

## Read next

- [Freshness and model](model-and-freshness.md)
- [Kernel outcomes](../../modules/kernel.md)
- [Hosted publication](../../runtime/hosted-composition-and-publication.md)
