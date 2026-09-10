---
type: Glossary Term
title: Evidence authority
description: The narrow boundary permitted to establish a fact or perform an effect, ordered from compiler and schema proof down to observation and text.
resource: file://AGENTS.md
tags: [glossary, evidence, authority, effects]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: AGENTS.md
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLeaseGuard]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
    symbols: [SemanticReadAuthority, LiveSemanticReadAuthority, SemanticReadValidationPort]
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/EditableMutationTarget.kt
  - path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAuthority.kt
---

# Evidence authority

Evidence authority is the strongest boundary allowed to establish a claim or perform an effect. Kast prefers compiler proof over schema proof, structured semantic evidence over runtime observation, and observation over text or heuristic inference.

Capabilities make authority explicit. A semantic lease guard can authorize a read while a generation remains current; mutation authority can perform only the writes admitted by a plan. Failure to acquire the required authority is a typed failure, not permission to use weaker evidence.

`SemanticReadAuthority` is closed over published leases and live IDE admission.
The original live owner must admit current epoch evidence before a detached
reference can participate in a read. Service validation and native selector
revalidation preserve freshness through the read. The `requirePublished`
refinement rejects live authority at effects that require a publication;
an editable mutation target still carries a published `SemanticReadLease`.
