---
type: Kotlin Contract
title: Source failure origin
description: Source reads distinguish invalid fields, rejected references, and failed internal obligations without disclosing input bytes.
resource: file://protocol/contract
tags: [source, failure, protocol]
timestamp: 2026-09-16T00:00:00Z
code_sources:
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceReadReferenceAdmission.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceExecutionBudgetIngress.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceRequestAdmission.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceRequestFieldReader.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceReadFailureDetails.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceRequestIngress.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSourceReadProtocol.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceReferenceFailures.kt
  - path: source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/CanonicalReadRejectionSchemas.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalReadRejectedDocument.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/SourceInputRejectionEvidence.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/provider/SourceInputRejectionTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/SourceFailureMatrixTest.kt
---

# Source failure origin

`SourceReadCause` retains existing finite read conditions and three disjoint
failure objects. `AdmittedSourceReadRejection` adds the granted budget without
replacing the cause. Compact and expanded reads use the same failure contract. Source cause and
request-expectation objects own the `type` discriminator, including when nested
inside a transport whose outer discriminator differs.

- `request-rejected` identifies an authored field path, bounded collection
  positions where needed, a closed rule, and applicable alternatives or bounds.
  Supplied execution-budget controls retain their exact nested field and integer
  bounds; absent and null optional controls retain default selection.
- `reference-rejected` retains the reference role and the cause known by the
  existing lookup authority. A missing retained handle is unavailable; it does
  not prove staleness, expiry, or foreign ownership.
- `internal-contract-failure` identifies a failed context, snapshot, scope,
  visibility, admission, or projection obligation. Recovery is to report the
  failure, rather than change a request without evidence of a request defect.

Physical source ingress uses the typed request serializer and finite validation
owner. It rejects malformed input before provider startup or source execution.
Valid unordered declaration-kind and visibility selections are accepted and
normalized by their existing domain owners; ordering is not a caller predicate.
Native and retained-output continuation syntax remains distinct and supported.

Diagnostics contain only finite authored labels and bounded indexes. They do not
contain source text, parser messages, raw tokens, or stack traces. Recovery labels
grant no implicit refresh, retry, or reacquisition effect.

The source service checks its admitted context and returned snapshot before
publication. Snapshot contradictions remain internal failures. Projection failures
retain separate result and qualification obligations. Source enumeration retains its coverage contract. Fresh symbol-anchored reads
may carry optional refreshed-handle metadata; expanded and compact projections
preserve it. Reacquisition failures retain their exact reason, including work
and time exhaustion, and do not become generic stale-source errors.

This contract does not establish the cause of any unobserved enterprise failure.
Installed and released-byte qualification remains separate from unit and schema
verification.
