---
type: Kotlin Contract
title: Retired installed knowledge contract
description: Control rejects the retired installed knowledge payload; repository citation checks and module reports remain separate.
resource: file://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
tags: [knowledge, cli, distribution, syntax, guidance]
code_sources:
  - path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
  - path: build.gradle.kts
  - path: packaging/internal/verification/run-installed-product.py
sources:
  - id: openwiki-source-6d6c58a5bfeff1b2388a6735
    resource: repo://build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - id: openwiki-source-a387d9d545afff426d34a10a
    resource: repo://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
  - id: openwiki-source-d1f8b0a2ac011ef2d36005a5
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
  - id: openwiki-source-27b11dfabcb3ca89e348f52d
    resource: repo://packaging/internal/verification/run-installed-product.py
generated: { by: "amp", at: "2026-11-15T20:10:47.711Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-21T22:16:17.145Z
---

# Retired installed knowledge contract

The installed knowledge bundle and its retrieval implementation are retired.
`VerifyControlDistributionTask` rejects `share/kast/knowledge` and every descendant as a retired payload.
Its required-entry list no longer includes a knowledge manifest as a product requirement.
The former descriptor, guide-resource, and installed-reader contracts do not describe the current Control payload.

The Control archive keeps its 16,384-entry limit.
The separate installed-tree traversal allowance is 32,768 entries, including the separately admitted pinned interpreter.
Removing the knowledge payload does not raise the archive limit.

Repository knowledge remains distinct from installed resources.
`generateKastModuleKnowledge` still writes the verified architecture and scoped-guide report under `build/reports/kast-architecture/`.
Read [repository knowledge](../knowledge/installed-projection-and-retrieval.md) for that report and the retained citation scanner.

## Acceptance remains separate

`installedProductTest` delegates complete-product native lifecycle acceptance
to `product_lifecycle_probe.py`. It requires explicit candidate and baseline
products, private Python and dependency artifacts, target, evidence and receipt
paths. Component archive checks do not establish native acceptance.
