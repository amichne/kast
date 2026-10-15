---
type: System Explanation
title: Callback supply and invocation bindings
description: Traces callback lexical owners, actual-to-formal compiler bindings, immutable
  values, factories, declared dependency effects, and unresolved flows.
tags:
- kast
- semantics
code_sources:
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackBindingReader.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSupplierInventory.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijImmutableCallbackValueResolver.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackFactoryReturn.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackDependencyContract.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackInvocationFlow.kt
- path: relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/KotlinCallbackSupplyTest.kt
- path: relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/DeclaredCallbackInvocationKindTest.kt
- path: relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RequiredCallbackInvocationProofTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-cf837289cfde2b7e2a175958
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt
  - id: openwiki-source-e8a3d9c736400ce0a6f4c247
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackBindingReader.kt
  - id: openwiki-source-83fc669045de1f95291d1bb5
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackDependencyContract.kt
  - id: openwiki-source-7a39aa76a4978e328131bbf1
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackInvocationFlow.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Callback supply and invocation bindings

## Lexical ownership does not prove invocation

A lambda or anonymous function can sit inside a named declaration without executing when that declaration runs.
The relation model therefore keeps callback observations separate from named caller edges.
The native reader records the supplying owner, callable body, actual value, mapped parameter, and observed invocation route.
Missing supply or activation evidence remains an explicit obligation.

`CallbackArgumentBinding` uses the compiler-mapped callable and parameter position.
It does not use the argument's lexical position as formal identity.
Admission makes sure that the invocation belongs to its supplying owner and the parameter belongs to the selected callable.
Named arguments and reordered actual values therefore keep the formal identity that the compiler established.

## Follow supported supply routes

The supplier inventory examines the selected domain before it claims complete supply coverage.
The native readers handle supported direct values, immutable aliases, forwarding, named references, and factory return routes through distinct typed observations.
Each route keeps its original ownership and occurrence evidence.
Unsupported mutation, ambiguous values, missing bodies, or incomplete scans do not silently disappear.

An invocation inside a nested body keeps that body's identity.
The model does not replace it with the nearest convenient named ancestor.
Factory capture and returned callback evidence remain distinct from a direct call at the supplying site.
The flow records unresolved obligations when the necessary execution relationship remains unproved.

## Dependency callbacks need explicit authority

Dependency PSI bodies do not supply proof for external callable behavior.
`IntellijCallbackDependencyContract` reads supported declared effects or the exact reviewed `kotlin.lazy` model.
The lazy model keeps deferred behavior and binary evidence distinct from calls-in-place.
An arbitrary external function does not inherit that reviewed behavior from a matching name.

Native invocation flow combines admitted body, binding, owner, supply, and bounded work evidence.
Inline policy, exclusions, and unavailable causes remain visible through projection.
The complete graph owner decides whether those observations discharge the question's obligations.

Contract tests establish range and binding invariants.
Native callback fixtures establish compiler mappings under their controlled project.
Neither layer establishes actual runtime callback activation in an unmeasured application.

## Focused tests

- [KotlinCallbackSupplyTest.kt](../../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/KotlinCallbackSupplyTest.kt)
- [DeclaredCallbackInvocationKindTest.kt](../../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/DeclaredCallbackInvocationKindTest.kt)
- [RequiredCallbackInvocationProofTest.kt](../../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RequiredCallbackInvocationProofTest.kt)

## Source owners

- [CallbackArgumentBinding.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt)
- [IntellijCallbackBindingReader.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackBindingReader.kt)
- [IntellijCallbackSupplierInventory.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSupplierInventory.kt)
- [IntellijImmutableCallbackValueResolver.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijImmutableCallbackValueResolver.kt)
- [IntellijCallbackFactoryReturn.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackFactoryReturn.kt)
- [IntellijCallbackDependencyContract.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackDependencyContract.kt)
- [IntellijCallbackInvocationFlow.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackInvocationFlow.kt)

## Read next

- [Graph completion and reuse](callback-reuse-and-completion.md)
- [Named relationships](named-relations.md)
