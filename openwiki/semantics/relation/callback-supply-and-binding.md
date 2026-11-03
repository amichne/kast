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
sources:
  - id: openwiki-source-3a2deddcdbc6513d70de57e5
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackDocumentAdmission.kt
  - id: openwiki-source-6ee6a10decc8306b2fc74bca
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackObservationDocument.kt
  - id: openwiki-source-f19a73beb201e7715627b4c0
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackNavigationProjection.kt
  - id: openwiki-source-cf837289cfde2b7e2a175958
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt
  - id: openwiki-source-e8a3d9c736400ce0a6f4c247
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackBindingReader.kt
  - id: openwiki-source-83fc669045de1f95291d1bb5
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackDependencyContract.kt
  - id: openwiki-source-7a39aa76a4978e328131bbf1
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackInvocationFlow.kt
generated: { by: "codex", at: "2026-11-03T06:09:31.584Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-03T06:09:31.584Z
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

## Public observation variants

Each callback observation keeps its exact source occurrence, lexical container, anonymous body, and named-edge policy.
Lexical containment does not turn the enclosing named function into the caller of anonymous-body calls.
The evidence does not prove runtime execution.

| Named-edge policy | Meaning |
| --- | --- |
| EXCLUDED | The named edge is absent with its reason and source occurrence. |
| ADMITTED_DIRECT | Compiler proof admits a direct named edge and keeps the anonymous body. |
| ADMITTED_INLINE | Inline policy admits a named edge and keeps the anonymous owner. |
| UNAVAILABLE | Ownership remains incomplete. |

BOUND maps an explicit argument to its compiler-selected formal.
DEFAULT keeps the defining formal and default expression without inventing an explicit argument.
DIRECT keeps a direct literal invocation owner without a receiving formal.
DEPENDENCY_CONTRACT keeps qualified binary calls-in-place evidence.
DEFERRED_DEPENDENCY keeps initializer supply without an invocation kind or runtime count.
UNAVAILABLE preserves a missing mapping.

EXHAUSTIVE means that the admitted formal scan finished.
An empty exhaustive scan proves absence only in its supported scanned flow.
INCOMPLETE prevents that absence claim.
NOT_APPLICABLE describes a directly invoked body without a formal to scan.
Each parameter call keeps its forwardings array, including an empty array.
Forwarding hops keep source formal, supplied expression, and destination binding.
`owner_bindings` keeps nested supply, mapping, and recursive execution proof.
Missing execution proof keeps NESTED_CALLBACK_EXECUTION or its more specific cause.
`callable_observations` keeps symbolic formal calls and compiler targets without source.

## Static route navigation

Relation and WALK observations keep navigation without inventing named caller edges.
PROVEN supplies supported steps from the named lexical owner.
UNPROVEN keeps an established prefix, cause, and original unresolved flow.
NOT_COMPUTED proves no route.
Each step keeps source, target, depth, and supply, forwarding, or invocation evidence.
Formal nodes keep the supplier binding. Distinct wrapper calls do not share nodes merely through a common formal.
Breadth-first depth starts at one and counts transitions.
Supply and invocation are separate transitions. Supply alone cannot reach an anonymous body.
WALK.maximumDepth limits named expansion without trimming an observation's supported route.
Retained pages preserve that route without new semantic reads.

## Dependency effects and incomplete proof

Declared effects are EXACTLY_ONCE, AT_MOST_ONCE, AT_LEAST_ONCE, and explicitly declared UNKNOWN.
The compiler selects the formal. The effect remains bound to that formal and its qualified binary identity.
Missing, repeated, and unsupported effects keep DEPENDENCY_CONTRACT_EFFECT_UNPROVEN.
These effects supply neither runtime counts nor dependency-body invocation sites.

External mapping keeps EXTERNAL_CALLABLE and OUTSIDE_DOMAIN.
Stored and returned callbacks keep STORED_CALLBACK and RETURNED_CALLBACK.
Unsupported supply and recursive forwarding keep UNSUPPORTED_CALLBACK_SUPPLY and CALLBACK_CYCLE.
FINALLY_UNSUPPORTED and ABRUPT_COMPLETION preserve unsupported control-flow effects.
Work, time, result, and byte limits keep their separate causes.
An empty call list cannot erase an incomplete scan or unresolved proof.

The anonymous body's issued `candidateSelector` supplies READ_SOURCE.candidateRef under its admitted source snapshot.
It does not select the enclosing named function.
The [public field reference](../../../docs/public/reference/callback-observations.mdx) keeps its field tables and literal READ_SOURCE request.

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
