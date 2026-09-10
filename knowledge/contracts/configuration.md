---
type: API Contract
title: Installation configuration
description: Every external configuration input has declared ownership, parsing, defaults, and projection before it can affect a managed runtime.
resource: file://distribution/contract
tags: [configuration, distribution, installation]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationSchema.kt
    symbols: [KastConfigurationSchema]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt
    symbols: [SavedConfigurationIngress]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/SavedConfigurationAdmissionTest.kt
  - path: packaging/configuration-schema.json
  - path: build-policy/configuration-ingress.json
---

# Installation configuration

The Kotlin catalogue owns typed keys, value parsing, defaults, and consuming component ownership. Installed-runtime composition admits saved configuration, then projects it to runtime owners instead of reading it ambiently throughout the system. Mutation commands retain this admission before runtime startup. The default semantic read entry point selects the existing IDE before installed composition; a rejected installed configuration cannot make it launch a worker or replace a missing-host rejection. The process-boundary checks cover both paths and verify that rejection creates no runtime/cache directories.

Two checked artifacts enforce the boundary: [configuration-schema.json](../../packaging/configuration-schema.json) describes the external document and [configuration-ingress.json](../../build-policy/configuration-ingress.json) declares permitted ingress owners. Root verification rejects undeclared ambient reads.

See [distribution](../modules/distribution.md).
