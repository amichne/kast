---
type: System Explanation
title: Value flow and representation models
description: Explains exact native value sites, bounded one-hop transfers, producer
  seeds, reviewed representation models, and unresolved flow obligations.
tags:
- kast
- semantics
code_sources:
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueSite.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueFlow.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueProducerSeed.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationModel.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationProvenance.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueFlowCompilerAdapter.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueSiteRevalidationNative.kt
- path: relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/ValueSiteRestorationTest.kt
- path: relation/service/src/test/kotlin/io/github/amichne/kast/relation/service/RepresentationPropagationTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-b68db27640f24c1cb0a92923
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationModel.kt
  - id: openwiki-source-c5f0a7cc49121c1f25d33773
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationProvenance.kt
  - id: openwiki-source-aaaaf5388ee0a1374beff910
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueFlow.kt
  - id: openwiki-source-b237fe55f86308ccb96d0596
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueFlowCompilerAdapter.kt
  - id: openwiki-source-5e4eb99eb5fd7a5dcae4b5eb
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueSiteRevalidationNative.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Value flow and representation models

## A value site keeps native identity

`ValueSite` identifies a compiler-grounded position inside an admitted exact declaration.
The position includes its role, source range, callable evidence, and original read authority.
Expression results, argument positions, local reads, and callable endpoints do not become interchangeable offsets.
Source text alone cannot establish a native role.

`IntellijValueSiteRevalidationNative` restores the expected Kotlin PSI shape before role and compiler binding admission.
A syntactically similar expression at the same offset can still reject.
The adapter keeps restoration failures separate from unsupported semantic roles.
Fresh content and compiler identity remain necessary even when a detached site locator survives.

## One-hop native flow

The native value-flow adapter supplies bounded transfers and work receipts under the current authority.
`ValueProducerSeed` admission establishes the exact producer invocation and current model endpoints.
The relation layer reports one-hop facts and finite unresolved obligations.
The query engine owns multi-hop scheduling, checkpoints, branch conservation, and final investigation completion.

Unsupported effects or unknown flow do not become an empty complete transfer set.
The result keeps the site, provenance, scope, grant, and reason that limits the conclusion.
Cross-workspace flow needs its independently admitted peer site and completed authority.
It does not follow from a matching serialized representation name.

## Reviewed models preserve their qualifications

`RepresentationModel` describes an admitted transformation between exact native positions.
Its provenance keeps reviewed model identity and representation history.
A model supplies its declared transformation only after native endpoint revalidation.
It does not prove arbitrary runtime serialization, storage, or consumer behavior.

Representation continuations keep explicit obligations when a relationship crosses the model's proven scope.
Native evidence and modeled evidence remain distinguishable in retained paths and compact witnesses.
An unresolved relationship stays unresolved when a presentation selects fewer rows.

Value-site tests establish restoration and typed role rejection.
Model tests establish pure transformations and provenance rules.
Installed cross-component behavior needs separate matched source and live-host evidence.

## Focused tests

- [ValueSiteRestorationTest.kt](../../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/ValueSiteRestorationTest.kt)
- [RepresentationPropagationTest.kt](../../../relation/service/src/test/kotlin/io/github/amichne/kast/relation/service/RepresentationPropagationTest.kt)

## Source owners

- [ValueSite.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueSite.kt)
- [ValueFlow.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueFlow.kt)
- [ValueProducerSeed.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueProducerSeed.kt)
- [RepresentationModel.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationModel.kt)
- [RepresentationProvenance.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationProvenance.kt)
- [IntellijValueFlowCompilerAdapter.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueFlowCompilerAdapter.kt)
- [IntellijValueSiteRevalidationNative.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueSiteRevalidationNative.kt)

## Read next

- [Impact execution](../../queries/impact-and-peer-proof.md)
- [Native relationship scope](named-relations.md)
